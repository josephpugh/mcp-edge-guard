package com.edwardjones.mcp.edge.grpc;

import com.edwardjones.mcp.edge.mcp.McpEnvelope;
import com.edwardjones.mcp.edge.policy.GuardDecision;
import com.edwardjones.mcp.edge.policy.PangeaGuardClient;
import com.edwardjones.mcp.edge.trace.TraceUtil;
import io.envoyproxy.envoy.service.ext_proc.v3.ExternalProcessorGrpc;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;

@GrpcService
public class McpEdgeExternalProcessor extends ExternalProcessorGrpc.ExternalProcessorImplBase {

    private static final Logger log = LoggerFactory.getLogger(McpEdgeExternalProcessor.class);

    private final PangeaGuardClient pangeaClient;
    private volatile Tracer tracer;

    @Autowired
    public McpEdgeExternalProcessor(PangeaGuardClient pangeaClient) {
        this.pangeaClient = pangeaClient;
    }

    McpEdgeExternalProcessor(PangeaGuardClient pangeaClient, Tracer tracer) {
        this.pangeaClient = pangeaClient;
        this.tracer = tracer;
    }

    private Tracer tracer() {
        Tracer t = tracer;
        if (t == null) {
            t = GlobalOpenTelemetry.getTracer("mcp-edge-guard");
            tracer = t;
        }
        return t;
    }

    @Override
    public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responseObserver) {
        log.debug("New ext-proc stream opened");

        return new StreamObserver<>() {
            private final SessionState session = new SessionState();

            @Override
            public void onNext(ProcessingRequest request) {
                try {
                    switch (request.getRequestCase()) {
                        case REQUEST_HEADERS -> handleRequestHeaders(request, session, responseObserver);
                        case REQUEST_BODY -> handleRequestBody(request, session, responseObserver);
                        case RESPONSE_HEADERS -> handleResponseHeaders(session, responseObserver);
                        case RESPONSE_BODY -> handleResponseBody(request, session, responseObserver);
                        default -> {
                            log.debug("Unhandled request case, continuing: {}", kv("requestCase", request.getRequestCase()));
                            responseObserver.onNext(ResponseBuilder.continueResponseHeaders());
                        }
                    }
                } catch (Exception e) {
                    log.error("Unexpected error processing ext-proc request: {} {}",
                            kv("requestCase", request.getRequestCase()),
                            kv("mcpMethod", session.mcpMethod), e);
                    if (session.guardSpan != null) {
                        session.guardSpan.recordException(e);
                        session.guardSpan.setStatus(StatusCode.ERROR, e.getMessage());
                    }
                    responseObserver.onNext(ResponseBuilder.immediateHttpError(500, "internal processing error"));
                }
            }

            @Override
            public void onError(Throwable t) {
                log.error("Stream error in ext-proc: {} {} {}",
                        kv("mcpMethod", session.mcpMethod),
                        kv("toolName", session.toolName),
                        kv("requestId", session.requestId), t);
                if (session.guardSpan != null) {
                    session.guardSpan.recordException(t);
                    session.guardSpan.setStatus(StatusCode.ERROR, t.getMessage());
                    session.guardSpan.end();
                }
            }

            @Override
            public void onCompleted() {
                log.info("Stream completed: {} {} {}",
                        kv("mcpMethod", session.mcpMethod),
                        kv("toolName", session.toolName),
                        kv("requestId", session.requestId));
                if (session.guardSpan != null) {
                    session.guardSpan.end();
                }
                responseObserver.onCompleted();
            }
        };
    }

    private void handleRequestHeaders(ProcessingRequest request, SessionState session,
                                      StreamObserver<ProcessingResponse> out) {
        var headerMap = request.getRequestHeaders().getHeaders();
        Map<String, String> headers = HeaderUtil.toMap(headerMap);

        Context parentContext = TraceUtil.extractParent(headers);

        session.guardSpan = tracer().spanBuilder("mcp.edge.guard")
                .setParent(parentContext)
                .setSpanKind(SpanKind.SERVER)
                .startSpan();

        session.guardContext = parentContext.with(session.guardSpan);

        TraceUtil.copyAllowedBaggageToSpan(session.guardSpan, parentContext);
        session.guardSpan.setAttribute("edge.component", "mcp-edge-guard");

        // Inject the guard span's trace context into headers sent upstream.
        // This makes the MCP server's spans children of our guard span.
        Map<String, String> propagatedHeaders = TraceUtil.injectTraceContext(session.guardContext);

        log.info("Request headers processed, trace context injected: {} {} {}",
                kv("traceId", session.guardSpan.getSpanContext().getTraceId()),
                kv("guardSpanId", session.guardSpan.getSpanContext().getSpanId()),
                kv("path", headers.get(":path")));
        out.onNext(ResponseBuilder.continueHeaders(propagatedHeaders));
    }

    private void handleRequestBody(ProcessingRequest request, SessionState session,
                                   StreamObserver<ProcessingResponse> out) {
        try (Scope ignored = session.guardSpan.makeCurrent()) {
            String rawBody = request.getRequestBody().getBody().toStringUtf8();

            McpEnvelope envelope;
            try {
                envelope = McpEnvelope.parse(rawBody);
            } catch (Exception e) {
                log.warn("Failed to parse MCP envelope, allowing request through: {} {}",
                        kv("bodyLength", rawBody.length()),
                        kv("bodyPreview", rawBody.substring(0, Math.min(200, rawBody.length()))), e);
                session.guardSpan.recordException(e);
                out.onNext(ResponseBuilder.continueRequestBody());
                return;
            }

            session.requestId = envelope.id();
            session.mcpMethod = envelope.method();
            session.toolName = envelope.toolName();

            if (envelope.method() != null) {
                session.guardSpan.setAttribute("mcp.method", envelope.method());
            }
            if (envelope.toolName() != null) {
                session.guardSpan.setAttribute("mcp.tool.name", envelope.toolName());
            }
            session.guardSpan.setAttribute("rpc.system", "jsonrpc");

            String recipe = "tools/call".equals(envelope.method())
                    ? "pangea_agent_pre_tool_guard"
                    : "pangea_prompt_guard";

            log.info("Evaluating request policy: {} {} {} {}",
                    kv("mcpMethod", envelope.method()),
                    kv("toolName", envelope.toolName()),
                    kv("requestId", envelope.id()),
                    kv("recipe", recipe));

            GuardDecision decision = pangeaClient.guard(recipe, envelope.policyText(), session.toolName);

            if (decision.blocked()) {
                session.guardSpan.setAttribute("security.blocked", true);
                session.guardSpan.setAttribute("security.phase", "request");
                if (decision.code() != null) {
                    session.guardSpan.setAttribute("security.code", decision.code());
                }
                session.guardSpan.setStatus(StatusCode.ERROR, "request blocked");

                log.warn("Request blocked by policy: {} {} {} {} {}",
                        kv("mcpMethod", envelope.method()),
                        kv("toolName", envelope.toolName()),
                        kv("requestId", envelope.id()),
                        kv("recipe", decision.recipe()),
                        kv("securityCode", decision.code()));

                out.onNext(ResponseBuilder.immediateJsonRpcError(
                        session.requestId, -32001, "Blocked by security policy", decision.code()));
                return;
            }

            log.debug("Request policy passed: {} {} {}",
                    kv("mcpMethod", envelope.method()),
                    kv("toolName", envelope.toolName()),
                    kv("requestId", envelope.id()));
            out.onNext(ResponseBuilder.continueRequestBody());
        } catch (Exception e) {
            log.error("Error processing request body: {} {}",
                    kv("mcpMethod", session.mcpMethod),
                    kv("requestId", session.requestId), e);
            session.guardSpan.recordException(e);
            session.guardSpan.setStatus(StatusCode.ERROR, "request inspection failed");
            out.onNext(ResponseBuilder.immediateHttpError(500, "security inspection failure"));
        }
    }

    private void handleResponseHeaders(SessionState session, StreamObserver<ProcessingResponse> out) {
        log.debug("Response headers received, continuing: {} {}",
                kv("mcpMethod", session.mcpMethod),
                kv("requestId", session.requestId));
        out.onNext(ResponseBuilder.continueResponseHeaders());
    }

    private void handleResponseBody(ProcessingRequest request, SessionState session,
                                    StreamObserver<ProcessingResponse> out) {
        try (Scope ignored = session.guardSpan.makeCurrent()) {
            String rawBody = request.getResponseBody().getBody().toStringUtf8();

            log.info("Evaluating response policy: {} {} {} {}",
                    kv("mcpMethod", session.mcpMethod),
                    kv("toolName", session.toolName),
                    kv("requestId", session.requestId),
                    kv("responseBodyLength", rawBody.length()));

            GuardDecision decision = pangeaClient.guard(
                    "pangea_agent_post_tool_guard", rawBody, session.toolName);

            if (decision.blocked()) {
                session.guardSpan.setAttribute("security.blocked", true);
                session.guardSpan.setAttribute("security.phase", "response");
                if (decision.code() != null) {
                    session.guardSpan.setAttribute("security.code", decision.code());
                }
                session.guardSpan.setStatus(StatusCode.ERROR, "response blocked");

                log.warn("Response blocked by policy: {} {} {} {} {}",
                        kv("mcpMethod", session.mcpMethod),
                        kv("toolName", session.toolName),
                        kv("requestId", session.requestId),
                        kv("recipe", decision.recipe()),
                        kv("securityCode", decision.code()));

                out.onNext(ResponseBuilder.replaceResponseWithJsonRpcError(
                        session.requestId, -32002, "Response blocked by security policy", decision.code()));
                return;
            }

            log.debug("Response policy passed: {} {} {}",
                    kv("mcpMethod", session.mcpMethod),
                    kv("toolName", session.toolName),
                    kv("requestId", session.requestId));
            out.onNext(ResponseBuilder.continueResponseBody());
        } catch (Exception e) {
            log.error("Error processing response body: {} {}",
                    kv("mcpMethod", session.mcpMethod),
                    kv("requestId", session.requestId), e);
            session.guardSpan.recordException(e);
            session.guardSpan.setStatus(StatusCode.ERROR, "response inspection failed");
            out.onNext(ResponseBuilder.immediateHttpError(500, "security inspection failure"));
        }
    }
}
