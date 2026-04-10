package com.edwardjones.mcp.edge.trace;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TraceUtilTest {

    @RegisterExtension
    static final OpenTelemetryExtension otelExtension = OpenTelemetryExtension.create();

    @Test
    void extractParentFromTraceparent() {
        Map<String, String> headers = new HashMap<>();
        headers.put("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

        Context ctx = TraceUtil.extractParent(headers);
        SpanContext spanCtx = Span.fromContext(ctx).getSpanContext();

        assertEquals("0af7651916cd43dd8448eb211c80319c", spanCtx.getTraceId());
        assertEquals("b7ad6b7169203331", spanCtx.getSpanId());
        assertTrue(spanCtx.getTraceFlags().isSampled());
    }

    @Test
    void extractParentWithBaggage() {
        Map<String, String> headers = new HashMap<>();
        headers.put("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        headers.put("baggage", "tenant.id=acme,user.id=alice,other.key=ignored");

        Context ctx = TraceUtil.extractParent(headers);
        Baggage baggage = Baggage.fromContext(ctx);

        assertEquals("acme", baggage.getEntryValue("tenant.id"));
        assertEquals("alice", baggage.getEntryValue("user.id"));
        assertEquals("ignored", baggage.getEntryValue("other.key"));
    }

    @Test
    void extractParentWithNoHeaders() {
        Map<String, String> headers = new HashMap<>();

        Context ctx = TraceUtil.extractParent(headers);
        SpanContext spanCtx = Span.fromContext(ctx).getSpanContext();

        assertFalse(spanCtx.isValid());
    }

    @Test
    void copyAllowedBaggageToSpan() {
        Span span = otelExtension.getOpenTelemetry().getTracer("test")
                .spanBuilder("test-span")
                .startSpan();

        Context ctx = Context.root()
                .with(Baggage.builder()
                        .put("tenant.id", "acme")
                        .put("user.id", "bob")
                        .put("session.id", "sess-123")
                        .put("secret.key", "should-not-copy")
                        .build());

        TraceUtil.copyAllowedBaggageToSpan(span, ctx);
        span.end();

        List<SpanData> spans = otelExtension.getSpans();
        SpanData spanData = spans.stream()
                .filter(s -> s.getName().equals("test-span"))
                .findFirst()
                .orElseThrow();

        assertEquals("acme", spanData.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("baggage.tenant.id")));
        assertEquals("bob", spanData.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("baggage.user.id")));
        assertEquals("sess-123", spanData.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("baggage.session.id")));
        assertNull(spanData.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("baggage.secret.key")));
    }

    @Test
    void copyBaggageWhenNonePresent() {
        Span span = otelExtension.getOpenTelemetry().getTracer("test")
                .spanBuilder("empty-baggage-span")
                .startSpan();

        TraceUtil.copyAllowedBaggageToSpan(span, Context.root());
        span.end();

        List<SpanData> spans = otelExtension.getSpans();
        SpanData spanData = spans.stream()
                .filter(s -> s.getName().equals("empty-baggage-span"))
                .findFirst()
                .orElseThrow();

        assertNull(spanData.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("baggage.tenant.id")));
    }

    @Test
    void injectTraceContext() {
        Span span = otelExtension.getOpenTelemetry().getTracer("test")
                .spanBuilder("inject-test")
                .startSpan();

        Context ctx = Context.root().with(span);

        Map<String, String> headers = TraceUtil.injectTraceContext(ctx);

        assertTrue(headers.containsKey("traceparent"));
        String traceparent = headers.get("traceparent");
        assertTrue(traceparent.startsWith("00-"));
        assertTrue(traceparent.contains(span.getSpanContext().getTraceId()));
        assertTrue(traceparent.contains(span.getSpanContext().getSpanId()));

        span.end();
    }

    @Test
    void injectTraceContextPreservesBaggage() {
        Span span = otelExtension.getOpenTelemetry().getTracer("test")
                .spanBuilder("inject-baggage-test")
                .startSpan();

        Context ctx = Context.root()
                .with(span)
                .with(Baggage.builder().put("tenant.id", "acme").build());

        Map<String, String> headers = TraceUtil.injectTraceContext(ctx);

        assertTrue(headers.containsKey("traceparent"));
        assertTrue(headers.containsKey("baggage"));
        assertTrue(headers.get("baggage").contains("tenant.id=acme"));

        span.end();
    }
}
