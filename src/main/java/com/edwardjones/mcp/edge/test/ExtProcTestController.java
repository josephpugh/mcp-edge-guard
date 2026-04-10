package com.edwardjones.mcp.edge.test;

import com.edwardjones.mcp.edge.grpc.McpEdgeExternalProcessor;
import com.google.protobuf.ByteString;
import io.envoyproxy.envoy.config.core.v3.HeaderMap;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.service.ext_proc.v3.*;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * REST endpoint that simulates the Flex Gateway ext-proc flow for local testing.
 * Accepts a plain MCP JSON-RPC body and runs it through the full
 * request headers -> request body -> response headers -> response body pipeline.
 */
@RestController
@RequestMapping("/test")
public class ExtProcTestController {

    private static final Logger log = LoggerFactory.getLogger(ExtProcTestController.class);

    private final McpEdgeExternalProcessor processor;

    public ExtProcTestController(McpEdgeExternalProcessor processor) {
        this.processor = processor;
    }

    @PostMapping("/evaluate")
    public ResponseEntity<Map<String, Object>> evaluate(
            @RequestBody String mcpBody,
            @RequestHeader(value = "traceparent", required = false) String traceparent,
            @RequestHeader(value = "baggage", required = false) String baggage,
            @RequestHeader(value = "X-Mock-Response", required = false) String mockResponse) throws Exception {

        log.info("Test evaluation request received: {} {}",
                kv("bodyLength", mcpBody.length()),
                kv("hasTraceparent", traceparent != null));

        // Build simulated request headers (what Flex would send)
        HeaderMap.Builder reqHeaders = HeaderMap.newBuilder()
                .addHeaders(HeaderValue.newBuilder().setKey(":method").setValue("POST").build())
                .addHeaders(HeaderValue.newBuilder().setKey(":path").setValue("/mcp").build())
                .addHeaders(HeaderValue.newBuilder().setKey("content-type").setValue("application/json").build());

        if (traceparent != null) {
            reqHeaders.addHeaders(HeaderValue.newBuilder().setKey("traceparent").setValue(traceparent).build());
        }
        if (baggage != null) {
            reqHeaders.addHeaders(HeaderValue.newBuilder().setKey("baggage").setValue(baggage).build());
        }

        // Default mock upstream response if none provided
        String upstreamResponse = mockResponse != null ? mockResponse :
                "{\"jsonrpc\":\"2.0\",\"id\":\"mock\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"mock response\"}]}}";

        // Collect responses from the processor
        List<ProcessingResponse> responses = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        StreamObserver<ProcessingResponse> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(ProcessingResponse value) {
                responses.add(value);
            }

            @Override
            public void onError(Throwable t) {
                errors.add(t);
                done.countDown();
            }

            @Override
            public void onCompleted() {
                done.countDown();
            }
        };

        // Open the bidirectional stream
        StreamObserver<ProcessingRequest> requestStream = processor.process(responseObserver);

        // Phase 1: Request headers
        requestStream.onNext(ProcessingRequest.newBuilder()
                .setRequestHeaders(HttpHeaders.newBuilder()
                        .setHeaders(reqHeaders.build())
                        .build())
                .build());

        // Check if request headers caused an immediate response (unlikely but possible)
        if (hasImmediateResponse(responses)) {
            requestStream.onCompleted();
            done.await(5, TimeUnit.SECONDS);
            return buildResult(responses, "request_headers");
        }

        // Phase 2: Request body
        requestStream.onNext(ProcessingRequest.newBuilder()
                .setRequestBody(HttpBody.newBuilder()
                        .setBody(ByteString.copyFromUtf8(mcpBody))
                        .setEndOfStream(true)
                        .build())
                .build());

        // Check if request was blocked
        if (hasImmediateResponse(responses)) {
            requestStream.onCompleted();
            done.await(5, TimeUnit.SECONDS);
            return buildResult(responses, "request_body");
        }

        // Phase 3: Response headers (simulated upstream response)
        requestStream.onNext(ProcessingRequest.newBuilder()
                .setResponseHeaders(HttpHeaders.newBuilder()
                        .setHeaders(HeaderMap.newBuilder()
                                .addHeaders(HeaderValue.newBuilder()
                                        .setKey(":status").setValue("200").build())
                                .addHeaders(HeaderValue.newBuilder()
                                        .setKey("content-type").setValue("application/json").build())
                                .build())
                        .build())
                .build());

        // Phase 4: Response body (simulated upstream response)
        requestStream.onNext(ProcessingRequest.newBuilder()
                .setResponseBody(HttpBody.newBuilder()
                        .setBody(ByteString.copyFromUtf8(upstreamResponse))
                        .setEndOfStream(true)
                        .build())
                .build());

        // Complete the stream
        requestStream.onCompleted();
        done.await(5, TimeUnit.SECONDS);

        if (!errors.isEmpty()) {
            log.error("Test evaluation stream error", errors.get(0));
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", errors.get(0).getMessage()));
        }

        return buildResult(responses, "completed");
    }

    private boolean hasImmediateResponse(List<ProcessingResponse> responses) {
        return responses.stream().anyMatch(ProcessingResponse::hasImmediateResponse);
    }

    private ResponseEntity<Map<String, Object>> buildResult(
            List<ProcessingResponse> responses, String stoppedAt) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stoppedAt", stoppedAt);

        List<Map<String, Object>> phases = new ArrayList<>();
        for (int i = 0; i < responses.size(); i++) {
            ProcessingResponse resp = responses.get(i);
            Map<String, Object> phase = new LinkedHashMap<>();

            if (resp.hasRequestHeaders()) {
                phase.put("phase", "request_headers");
                phase.put("action", "continue");
                var mutation = resp.getRequestHeaders().getResponse().getHeaderMutation();
                if (mutation.getSetHeadersCount() > 0) {
                    Map<String, String> injected = new LinkedHashMap<>();
                    mutation.getSetHeadersList().forEach(h ->
                            injected.put(h.getHeader().getKey(), h.getHeader().getValue()));
                    phase.put("injectedHeaders", injected);
                }
            } else if (resp.hasRequestBody()) {
                phase.put("phase", "request_body");
                phase.put("action", resp.getRequestBody().getResponse().getStatus().name().toLowerCase());
            } else if (resp.hasResponseHeaders()) {
                phase.put("phase", "response_headers");
                phase.put("action", "continue");
            } else if (resp.hasResponseBody()) {
                phase.put("phase", "response_body");
                String status = resp.getResponseBody().getResponse().getStatus().name().toLowerCase();
                phase.put("action", status);
                if (resp.getResponseBody().getResponse().hasBodyMutation()
                        && resp.getResponseBody().getResponse().getBodyMutation().hasBody()) {
                    phase.put("replacedBody", resp.getResponseBody().getResponse()
                            .getBodyMutation().getBody().toStringUtf8());
                }
            } else if (resp.hasImmediateResponse()) {
                phase.put("phase", "immediate_response");
                phase.put("action", "blocked");
                phase.put("httpStatus", resp.getImmediateResponse().getStatus().getCode().getNumber());
                phase.put("body", resp.getImmediateResponse().getBody().toStringUtf8());
            }

            phases.add(phase);
        }

        result.put("phases", phases);

        boolean blocked = responses.stream().anyMatch(r ->
                r.hasImmediateResponse() ||
                (r.hasResponseBody() && r.getResponseBody().getResponse().getStatus()
                        == CommonResponse.ResponseStatus.CONTINUE_AND_REPLACE));
        result.put("allowed", !blocked);

        log.info("Test evaluation complete: {} {} {}",
                kv("stoppedAt", stoppedAt),
                kv("allowed", !blocked),
                kv("phases", phases.size()));

        return ResponseEntity.ok(result);
    }
}
