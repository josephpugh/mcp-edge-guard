package com.edwardjones.mcp.edge.trace;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static net.logstash.logback.argument.StructuredArguments.kv;

public final class TraceUtil {

    private static final Logger log = LoggerFactory.getLogger(TraceUtil.class);

    static final Set<String> BAGGAGE_ALLOWLIST =
            Set.of("tenant.id", "user.id", "session.id");

    private static final TextMapGetter<Map<String, String>> GETTER =
            new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(Map<String, String> carrier) {
                    return carrier.keySet();
                }

                @Override
                public String get(Map<String, String> carrier, String key) {
                    return carrier.get(key);
                }
            };

    private static final TextMapSetter<Map<String, String>> SETTER = Map::put;

    public static Context extractParent(Map<String, String> headers) {
        Context ctx = W3CTraceContextPropagator.getInstance()
                .extract(Context.root(), headers, GETTER);
        ctx = W3CBaggagePropagator.getInstance()
                .extract(ctx, headers, GETTER);

        SpanContext spanCtx = Span.fromContext(ctx).getSpanContext();
        if (spanCtx.isValid()) {
            log.debug("Extracted parent trace context: {} {}",
                    kv("traceId", spanCtx.getTraceId()),
                    kv("parentSpanId", spanCtx.getSpanId()));
        } else {
            log.debug("No valid parent trace context found in headers");
        }

        return ctx;
    }

    public static void copyAllowedBaggageToSpan(Span span, Context ctx) {
        Baggage baggage = Baggage.fromContext(ctx);
        baggage.forEach((key, entry) -> {
            if (BAGGAGE_ALLOWLIST.contains(key)) {
                span.setAttribute("baggage." + key, entry.getValue());
                log.debug("Copied baggage to span: {} {}",
                        kv("baggageKey", key),
                        kv("baggageValue", entry.getValue()));
            }
        });
    }

    public static Map<String, String> injectTraceContext(Context context) {
        Map<String, String> headers = new HashMap<>();
        W3CTraceContextPropagator.getInstance().inject(context, headers, SETTER);
        W3CBaggagePropagator.getInstance().inject(context, headers, SETTER);

        log.debug("Injected trace context into upstream headers: {}",
                kv("traceparent", headers.get("traceparent")));

        return headers;
    }

    private TraceUtil() {
    }
}
