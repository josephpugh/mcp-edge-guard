package com.edwardjones.mcp.edge.grpc;

import com.edwardjones.mcp.edge.policy.GuardDecision;
import com.edwardjones.mcp.edge.policy.PangeaGuardClient;
import com.google.protobuf.ByteString;
import io.envoyproxy.envoy.config.core.v3.HeaderMap;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.service.ext_proc.v3.*;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class McpEdgeExternalProcessorTest {

    @RegisterExtension
    static final OpenTelemetryExtension otelExtension = OpenTelemetryExtension.create();

    @Rule
    public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

    private PangeaGuardClient mockPangea;
    private ManagedChannel channel;
    private ExternalProcessorGrpc.ExternalProcessorStub stub;
    private Tracer tracer;

    @BeforeEach
    void setUp() throws Exception {
        mockPangea = (recipe, text, toolName) ->
                new GuardDecision(recipe, false, null);

        tracer = otelExtension.getOpenTelemetry().getTracer("mcp-edge-guard-test");

        String serverName = InProcessServerBuilder.generateName();

        grpcCleanup.register(InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new McpEdgeExternalProcessor(mockPangea, tracer))
                .build()
                .start());

        channel = grpcCleanup.register(
                InProcessChannelBuilder.forName(serverName).directExecutor().build());

        stub = ExternalProcessorGrpc.newStub(channel);
    }

    // -- Helper methods --

    private ProcessingRequest requestHeaders(String traceparent, String baggage) {
        HeaderMap.Builder hm = HeaderMap.newBuilder()
                .addHeaders(HeaderValue.newBuilder().setKey(":method").setValue("POST").build())
                .addHeaders(HeaderValue.newBuilder().setKey(":path").setValue("/mcp").build())
                .addHeaders(HeaderValue.newBuilder().setKey("content-type").setValue("application/json").build());

        if (traceparent != null) {
            hm.addHeaders(HeaderValue.newBuilder().setKey("traceparent").setValue(traceparent).build());
        }
        if (baggage != null) {
            hm.addHeaders(HeaderValue.newBuilder().setKey("baggage").setValue(baggage).build());
        }

        return ProcessingRequest.newBuilder()
                .setRequestHeaders(HttpHeaders.newBuilder()
                        .setHeaders(hm.build())
                        .build())
                .build();
    }

    private ProcessingRequest requestBody(String jsonBody) {
        return ProcessingRequest.newBuilder()
                .setRequestBody(HttpBody.newBuilder()
                        .setBody(ByteString.copyFromUtf8(jsonBody))
                        .setEndOfStream(true)
                        .build())
                .build();
    }

    private ProcessingRequest responseHeaders() {
        return ProcessingRequest.newBuilder()
                .setResponseHeaders(HttpHeaders.newBuilder()
                        .setHeaders(HeaderMap.newBuilder()
                                .addHeaders(HeaderValue.newBuilder()
                                        .setKey(":status").setValue("200").build())
                                .addHeaders(HeaderValue.newBuilder()
                                        .setKey("content-type").setValue("application/json").build())
                                .build())
                        .build())
                .build();
    }

    private ProcessingRequest responseBody(String jsonBody) {
        return ProcessingRequest.newBuilder()
                .setResponseBody(HttpBody.newBuilder()
                        .setBody(ByteString.copyFromUtf8(jsonBody))
                        .setEndOfStream(true)
                        .build())
                .build();
    }

    private static class CollectingObserver implements StreamObserver<ProcessingResponse> {
        final List<ProcessingResponse> responses = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch completedLatch = new CountDownLatch(1);
        volatile Throwable error;

        @Override
        public void onNext(ProcessingResponse value) {
            responses.add(value);
        }

        @Override
        public void onError(Throwable t) {
            error = t;
            completedLatch.countDown();
        }

        @Override
        public void onCompleted() {
            completedLatch.countDown();
        }

        void awaitCompletion() throws InterruptedException {
            assertTrue(completedLatch.await(5, TimeUnit.SECONDS), "Stream did not complete in time");
        }
    }

    // -- Tests --

    @Test
    void fullHappyPath_toolsCall() throws Exception {
        CollectingObserver observer = new CollectingObserver();
        StreamObserver<ProcessingRequest> requestStream = stub.process(observer);

        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        String body = """
                {"jsonrpc":"2.0","id":"1","method":"tools/call","params":{"name":"get_weather","arguments":{"city":"NYC"}}}
                """;
        String responseJson = """
                {"jsonrpc":"2.0","id":"1","result":{"content":[{"type":"text","text":"sunny"}]}}
                """;

        requestStream.onNext(requestHeaders(traceparent, "tenant.id=acme,user.id=alice"));
        requestStream.onNext(requestBody(body));
        requestStream.onNext(responseHeaders());
        requestStream.onNext(responseBody(responseJson));
        requestStream.onCompleted();

        observer.awaitCompletion();
        assertNull(observer.error);
        assertEquals(4, observer.responses.size());

        // 1. Request headers response should contain traceparent mutation
        ProcessingResponse headersResp = observer.responses.get(0);
        assertTrue(headersResp.hasRequestHeaders());
        var headerMutation = headersResp.getRequestHeaders().getResponse().getHeaderMutation();
        assertTrue(headerMutation.getSetHeadersList().stream()
                .anyMatch(h -> h.getHeader().getKey().equals("traceparent")));

        // 2. Request body response should be CONTINUE
        ProcessingResponse bodyResp = observer.responses.get(1);
        assertTrue(bodyResp.hasRequestBody());
        assertEquals(CommonResponse.ResponseStatus.CONTINUE,
                bodyResp.getRequestBody().getResponse().getStatus());

        // 3. Response headers should be CONTINUE
        ProcessingResponse respHeadersResp = observer.responses.get(2);
        assertTrue(respHeadersResp.hasResponseHeaders());

        // 4. Response body should be CONTINUE
        ProcessingResponse respBodyResp = observer.responses.get(3);
        assertTrue(respBodyResp.hasResponseBody());
        assertEquals(CommonResponse.ResponseStatus.CONTINUE,
                respBodyResp.getResponseBody().getResponse().getStatus());

        // Verify spans
        List<SpanData> spans = otelExtension.getSpans();
        assertTrue(spans.stream().anyMatch(s -> s.getName().equals("mcp.edge.guard")));
    }

    @Test
    void requestBlockedByPolicy() throws Exception {
        mockPangea = (recipe, text, toolName) -> {
            if (recipe.equals("pangea_agent_pre_tool_guard")) {
                return new GuardDecision(recipe, true, "PII_DETECTED");
            }
            return new GuardDecision(recipe, false, null);
        };

        // Rebuild server with blocking pangea
        String serverName = InProcessServerBuilder.generateName();
        grpcCleanup.register(InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new McpEdgeExternalProcessor(mockPangea, tracer))
                .build()
                .start());
        channel = grpcCleanup.register(
                InProcessChannelBuilder.forName(serverName).directExecutor().build());
        stub = ExternalProcessorGrpc.newStub(channel);

        CollectingObserver observer = new CollectingObserver();
        StreamObserver<ProcessingRequest> requestStream = stub.process(observer);

        String body = """
                {"jsonrpc":"2.0","id":"42","method":"tools/call","params":{"name":"get_ssn"}}
                """;

        requestStream.onNext(requestHeaders(null, null));
        requestStream.onNext(requestBody(body));
        requestStream.onCompleted();

        observer.awaitCompletion();
        assertNull(observer.error);
        assertEquals(2, observer.responses.size());

        // Second response should be ImmediateResponse (blocked)
        ProcessingResponse blocked = observer.responses.get(1);
        assertTrue(blocked.hasImmediateResponse());
        assertEquals(io.envoyproxy.envoy.type.v3.StatusCode.Forbidden,
                blocked.getImmediateResponse().getStatus().getCode());
        assertTrue(blocked.getImmediateResponse().getBody().toStringUtf8().contains("\"id\":\"42\""));
        assertTrue(blocked.getImmediateResponse().getBody().toStringUtf8().contains("Blocked by security policy"));
        assertTrue(blocked.getImmediateResponse().getBody().toStringUtf8().contains("PII_DETECTED"));
    }

    @Test
    void responseBlockedByPolicy() throws Exception {
        mockPangea = (recipe, text, toolName) -> {
            if (recipe.equals("pangea_agent_post_tool_guard")) {
                return new GuardDecision(recipe, true, "MALICIOUS_CONTENT");
            }
            return new GuardDecision(recipe, false, null);
        };

        String serverName = InProcessServerBuilder.generateName();
        grpcCleanup.register(InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new McpEdgeExternalProcessor(mockPangea, tracer))
                .build()
                .start());
        channel = grpcCleanup.register(
                InProcessChannelBuilder.forName(serverName).directExecutor().build());
        stub = ExternalProcessorGrpc.newStub(channel);

        CollectingObserver observer = new CollectingObserver();
        StreamObserver<ProcessingRequest> requestStream = stub.process(observer);

        String reqBody = """
                {"jsonrpc":"2.0","id":"5","method":"tools/call","params":{"name":"query_db"}}
                """;
        String respBody = """
                {"jsonrpc":"2.0","id":"5","result":{"content":[{"type":"text","text":"malicious data"}]}}
                """;

        requestStream.onNext(requestHeaders(null, null));
        requestStream.onNext(requestBody(reqBody));
        requestStream.onNext(responseHeaders());
        requestStream.onNext(responseBody(respBody));
        requestStream.onCompleted();

        observer.awaitCompletion();
        assertNull(observer.error);
        assertEquals(4, observer.responses.size());

        // Response body should be replaced with error
        ProcessingResponse respBodyResp = observer.responses.get(3);
        assertTrue(respBodyResp.hasResponseBody());
        assertEquals(CommonResponse.ResponseStatus.CONTINUE_AND_REPLACE,
                respBodyResp.getResponseBody().getResponse().getStatus());

        ByteString replacedBody = respBodyResp.getResponseBody().getResponse()
                .getBodyMutation().getBody();
        String replacedJson = replacedBody.toStringUtf8();
        assertTrue(replacedJson.contains("\"id\":\"5\""));
        assertTrue(replacedJson.contains("Response blocked by security policy"));
        assertTrue(replacedJson.contains("MALICIOUS_CONTENT"));
    }

    @Test
    void traceContextInjectedIntoRequestHeaders() throws Exception {
        CollectingObserver observer = new CollectingObserver();
        StreamObserver<ProcessingRequest> requestStream = stub.process(observer);

        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        requestStream.onNext(requestHeaders(traceparent, null));
        requestStream.onCompleted();

        observer.awaitCompletion();
        assertEquals(1, observer.responses.size());

        ProcessingResponse resp = observer.responses.get(0);
        var mutation = resp.getRequestHeaders().getResponse().getHeaderMutation();
        var traceparentHeader = mutation.getSetHeadersList().stream()
                .filter(h -> h.getHeader().getKey().equals("traceparent"))
                .findFirst();

        assertTrue(traceparentHeader.isPresent());
        String newTraceparent = traceparentHeader.get().getHeader().getValue();
        // Should preserve the trace ID but have a different span ID (the guard span)
        assertTrue(newTraceparent.contains("0af7651916cd43dd8448eb211c80319c"),
                "Trace ID should be preserved");
        assertFalse(newTraceparent.contains("b7ad6b7169203331"),
                "Span ID should be different (guard span, not original)");
    }

    @Test
    void baggageCopiedToGuardSpan() throws Exception {
        CollectingObserver observer = new CollectingObserver();
        StreamObserver<ProcessingRequest> requestStream = stub.process(observer);

        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        requestStream.onNext(requestHeaders(traceparent, "tenant.id=acme,session.id=s1,ignored.key=val"));
        requestStream.onCompleted();

        observer.awaitCompletion();

        List<SpanData> spans = otelExtension.getSpans();
        SpanData guardSpan = spans.stream()
                .filter(s -> s.getName().equals("mcp.edge.guard"))
                .findFirst()
                .orElseThrow();

        assertEquals("acme", guardSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("baggage.tenant.id")));
        assertEquals("s1", guardSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("baggage.session.id")));
        assertNull(guardSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("baggage.ignored.key")));
    }

    @Test
    void mcpAttributesSetOnGuardSpan() throws Exception {
        CollectingObserver observer = new CollectingObserver();
        StreamObserver<ProcessingRequest> requestStream = stub.process(observer);

        String body = """
                {"jsonrpc":"2.0","id":"7","method":"tools/call","params":{"name":"search_docs"}}
                """;

        requestStream.onNext(requestHeaders(null, null));
        requestStream.onNext(requestBody(body));
        requestStream.onCompleted();

        observer.awaitCompletion();

        List<SpanData> spans = otelExtension.getSpans();
        SpanData guardSpan = spans.stream()
                .filter(s -> s.getName().equals("mcp.edge.guard"))
                .findFirst()
                .orElseThrow();

        assertEquals("tools/call", guardSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("mcp.method")));
        assertEquals("search_docs", guardSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("mcp.tool.name")));
        assertEquals("jsonrpc", guardSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("rpc.system")));
    }

    @Test
    void nonToolMethodUsesDifferentRecipe() throws Exception {
        final List<String> recipesUsed = Collections.synchronizedList(new ArrayList<>());
        mockPangea = (recipe, text, toolName) -> {
            recipesUsed.add(recipe);
            return new GuardDecision(recipe, false, null);
        };

        String serverName = InProcessServerBuilder.generateName();
        grpcCleanup.register(InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new McpEdgeExternalProcessor(mockPangea, tracer))
                .build()
                .start());
        channel = grpcCleanup.register(
                InProcessChannelBuilder.forName(serverName).directExecutor().build());
        stub = ExternalProcessorGrpc.newStub(channel);

        CollectingObserver observer = new CollectingObserver();
        StreamObserver<ProcessingRequest> requestStream = stub.process(observer);

        String body = """
                {"jsonrpc":"2.0","id":"1","method":"resources/list"}
                """;

        requestStream.onNext(requestHeaders(null, null));
        requestStream.onNext(requestBody(body));
        requestStream.onCompleted();

        observer.awaitCompletion();

        assertTrue(recipesUsed.contains("pangea_prompt_guard"),
                "Non-tool methods should use pangea_prompt_guard recipe");
    }

    @Test
    void malformedBodyContinuesWithoutBlocking() throws Exception {
        CollectingObserver observer = new CollectingObserver();
        StreamObserver<ProcessingRequest> requestStream = stub.process(observer);

        requestStream.onNext(requestHeaders(null, null));
        requestStream.onNext(requestBody("this is not json!!!"));
        requestStream.onCompleted();

        observer.awaitCompletion();
        assertNull(observer.error);
        assertEquals(2, observer.responses.size());

        // Should continue (not block) when body can't be parsed
        ProcessingResponse bodyResp = observer.responses.get(1);
        assertTrue(bodyResp.hasRequestBody());
        assertEquals(CommonResponse.ResponseStatus.CONTINUE,
                bodyResp.getRequestBody().getResponse().getStatus());
    }

    @Test
    void streamErrorEndsGuardSpan() throws Exception {
        CollectingObserver observer = new CollectingObserver();
        StreamObserver<ProcessingRequest> requestStream = stub.process(observer);

        requestStream.onNext(requestHeaders(null, null));
        requestStream.onError(new RuntimeException("stream broken"));

        // Give some time for error handling
        Thread.sleep(100);

        List<SpanData> spans = otelExtension.getSpans();
        SpanData guardSpan = spans.stream()
                .filter(s -> s.getName().equals("mcp.edge.guard"))
                .findFirst()
                .orElse(null);

        if (guardSpan != null) {
            assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR,
                    guardSpan.getStatus().getStatusCode());
        }
    }

    @Test
    void responseHeadersContinuePassthrough() throws Exception {
        CollectingObserver observer = new CollectingObserver();
        StreamObserver<ProcessingRequest> requestStream = stub.process(observer);

        requestStream.onNext(requestHeaders(null, null));
        requestStream.onNext(responseHeaders());
        requestStream.onCompleted();

        observer.awaitCompletion();
        assertEquals(2, observer.responses.size());

        ProcessingResponse respHeaders = observer.responses.get(1);
        assertTrue(respHeaders.hasResponseHeaders());
        assertEquals(CommonResponse.ResponseStatus.CONTINUE,
                respHeaders.getResponseHeaders().getResponse().getStatus());
    }

    @Test
    void guardSpanEncompassesFullLifecycle() throws Exception {
        CollectingObserver observer = new CollectingObserver();
        StreamObserver<ProcessingRequest> requestStream = stub.process(observer);

        String body = """
                {"jsonrpc":"2.0","id":"1","method":"tools/call","params":{"name":"test_tool"}}
                """;
        String respBody = """
                {"jsonrpc":"2.0","id":"1","result":{}}
                """;

        requestStream.onNext(requestHeaders(null, null));
        requestStream.onNext(requestBody(body));
        requestStream.onNext(responseHeaders());
        requestStream.onNext(responseBody(respBody));
        requestStream.onCompleted();

        observer.awaitCompletion();

        List<SpanData> spans = otelExtension.getSpans();
        SpanData guardSpan = spans.stream()
                .filter(s -> s.getName().equals("mcp.edge.guard"))
                .findFirst()
                .orElseThrow();

        // Guard span should have edge.component attribute
        assertEquals("mcp-edge-guard", guardSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("edge.component")));

        // Guard span should not be in error state for a happy path
        assertNotEquals(io.opentelemetry.api.trace.StatusCode.ERROR,
                guardSpan.getStatus().getStatusCode());
    }

    @Test
    void blockedRequestSetsSecurityAttributes() throws Exception {
        mockPangea = (recipe, text, toolName) ->
                new GuardDecision(recipe, true, "INJECTION_DETECTED");

        String serverName = InProcessServerBuilder.generateName();
        grpcCleanup.register(InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new McpEdgeExternalProcessor(mockPangea, tracer))
                .build()
                .start());
        channel = grpcCleanup.register(
                InProcessChannelBuilder.forName(serverName).directExecutor().build());
        stub = ExternalProcessorGrpc.newStub(channel);

        CollectingObserver observer = new CollectingObserver();
        StreamObserver<ProcessingRequest> requestStream = stub.process(observer);

        String body = """
                {"jsonrpc":"2.0","id":"1","method":"tools/call","params":{"name":"evil_tool"}}
                """;

        requestStream.onNext(requestHeaders(null, null));
        requestStream.onNext(requestBody(body));
        requestStream.onCompleted();

        observer.awaitCompletion();

        List<SpanData> spans = otelExtension.getSpans();
        SpanData guardSpan = spans.stream()
                .filter(s -> s.getName().equals("mcp.edge.guard"))
                .findFirst()
                .orElseThrow();

        assertEquals(true, guardSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.booleanKey("security.blocked")));
        assertEquals("request", guardSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("security.phase")));
        assertEquals("INJECTION_DETECTED", guardSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("security.code")));
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR,
                guardSpan.getStatus().getStatusCode());
    }

    @Test
    void requestInspectionFailureReturnsHttpError() throws Exception {
        mockPangea = (recipe, text, toolName) -> {
            throw new IllegalStateException("pangea unavailable");
        };

        String serverName = InProcessServerBuilder.generateName();
        grpcCleanup.register(InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new McpEdgeExternalProcessor(mockPangea, tracer))
                .build()
                .start());
        channel = grpcCleanup.register(
                InProcessChannelBuilder.forName(serverName).directExecutor().build());
        stub = ExternalProcessorGrpc.newStub(channel);

        CollectingObserver observer = new CollectingObserver();
        StreamObserver<ProcessingRequest> requestStream = stub.process(observer);

        String body = """
                {"jsonrpc":"2.0","id":"1","method":"tools/call","params":{"name":"search_docs"}}
                """;

        requestStream.onNext(requestHeaders(null, null));
        requestStream.onNext(requestBody(body));
        requestStream.onCompleted();

        observer.awaitCompletion();
        assertNull(observer.error);
        assertEquals(2, observer.responses.size());

        ProcessingResponse failure = observer.responses.get(1);
        assertTrue(failure.hasImmediateResponse());
        assertEquals(io.envoyproxy.envoy.type.v3.StatusCode.InternalServerError,
                failure.getImmediateResponse().getStatus().getCode());
        assertEquals("security inspection failure",
                failure.getImmediateResponse().getBody().toStringUtf8());

        SpanData guardSpan = otelExtension.getSpans().stream()
                .filter(s -> s.getName().equals("mcp.edge.guard"))
                .findFirst()
                .orElseThrow();

        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR,
                guardSpan.getStatus().getStatusCode());
    }

    @Test
    void responseInspectionFailureReturnsHttpError() throws Exception {
        mockPangea = (recipe, text, toolName) -> {
            if (recipe.equals("pangea_agent_post_tool_guard")) {
                throw new IllegalStateException("pangea unavailable");
            }
            return new GuardDecision(recipe, false, null);
        };

        String serverName = InProcessServerBuilder.generateName();
        grpcCleanup.register(InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new McpEdgeExternalProcessor(mockPangea, tracer))
                .build()
                .start());
        channel = grpcCleanup.register(
                InProcessChannelBuilder.forName(serverName).directExecutor().build());
        stub = ExternalProcessorGrpc.newStub(channel);

        CollectingObserver observer = new CollectingObserver();
        StreamObserver<ProcessingRequest> requestStream = stub.process(observer);

        String body = """
                {"jsonrpc":"2.0","id":"1","method":"tools/call","params":{"name":"search_docs"}}
                """;
        String responseJson = """
                {"jsonrpc":"2.0","id":"1","result":{"content":[{"type":"text","text":"result"}]}}
                """;

        requestStream.onNext(requestHeaders(null, null));
        requestStream.onNext(requestBody(body));
        requestStream.onNext(responseHeaders());
        requestStream.onNext(responseBody(responseJson));
        requestStream.onCompleted();

        observer.awaitCompletion();
        assertNull(observer.error);
        assertEquals(4, observer.responses.size());

        ProcessingResponse failure = observer.responses.get(3);
        assertTrue(failure.hasImmediateResponse());
        assertEquals(io.envoyproxy.envoy.type.v3.StatusCode.InternalServerError,
                failure.getImmediateResponse().getStatus().getCode());
        assertEquals("security inspection failure",
                failure.getImmediateResponse().getBody().toStringUtf8());

        SpanData guardSpan = otelExtension.getSpans().stream()
                .filter(s -> s.getName().equals("mcp.edge.guard"))
                .findFirst()
                .orElseThrow();

        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR,
                guardSpan.getStatus().getStatusCode());
    }

    @Test
    void responseBlockedSetsSecurityAttributes() throws Exception {
        mockPangea = (recipe, text, toolName) -> {
            if (recipe.equals("pangea_agent_post_tool_guard")) {
                return new GuardDecision(recipe, true, "MALICIOUS_CONTENT");
            }
            return new GuardDecision(recipe, false, null);
        };

        String serverName = InProcessServerBuilder.generateName();
        grpcCleanup.register(InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new McpEdgeExternalProcessor(mockPangea, tracer))
                .build()
                .start());
        channel = grpcCleanup.register(
                InProcessChannelBuilder.forName(serverName).directExecutor().build());
        stub = ExternalProcessorGrpc.newStub(channel);

        CollectingObserver observer = new CollectingObserver();
        StreamObserver<ProcessingRequest> requestStream = stub.process(observer);

        String body = """
                {"jsonrpc":"2.0","id":"1","method":"tools/call","params":{"name":"evil_tool"}}
                """;
        String responseJson = """
                {"jsonrpc":"2.0","id":"1","result":{"content":[{"type":"text","text":"bad"}]}}
                """;

        requestStream.onNext(requestHeaders(null, null));
        requestStream.onNext(requestBody(body));
        requestStream.onNext(responseHeaders());
        requestStream.onNext(responseBody(responseJson));
        requestStream.onCompleted();

        observer.awaitCompletion();

        List<SpanData> spans = otelExtension.getSpans();
        SpanData guardSpan = spans.stream()
                .filter(s -> s.getName().equals("mcp.edge.guard"))
                .findFirst()
                .orElseThrow();

        assertEquals(true, guardSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.booleanKey("security.blocked")));
        assertEquals("response", guardSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("security.phase")));
        assertEquals("MALICIOUS_CONTENT", guardSpan.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("security.code")));
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR,
                guardSpan.getStatus().getStatusCode());
    }

    @Test
    void unhandledRequestCaseContinues() throws Exception {
        CollectingObserver observer = new CollectingObserver();
        StreamObserver<ProcessingRequest> requestStream = stub.process(observer);

        requestStream.onNext(ProcessingRequest.getDefaultInstance());
        requestStream.onCompleted();

        observer.awaitCompletion();
        assertNull(observer.error);
        assertEquals(1, observer.responses.size());

        ProcessingResponse response = observer.responses.get(0);
        assertTrue(response.hasResponseHeaders());
        assertEquals(CommonResponse.ResponseStatus.CONTINUE,
                response.getResponseHeaders().getResponse().getStatus());
    }
}
