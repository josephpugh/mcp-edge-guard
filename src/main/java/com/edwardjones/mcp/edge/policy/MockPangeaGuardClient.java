package com.edwardjones.mcp.edge.policy;

import com.edwardjones.mcp.edge.trace.TraceUtil;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Component
public class MockPangeaGuardClient implements PangeaGuardClient {

    private static final Logger log = LoggerFactory.getLogger(MockPangeaGuardClient.class);
    private volatile Tracer tracer;

    public MockPangeaGuardClient() {
    }

    MockPangeaGuardClient(Tracer tracer) {
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
    public GuardDecision guard(String recipe, String text, String toolName) {
        Span policySpan = tracer().spanBuilder("pangea.policy.evaluate")
                .startSpan();

        TraceUtil.copyAllowedBaggageToSpan(policySpan, Context.current());

        try (Scope ignored = policySpan.makeCurrent()) {
            policySpan.setAttribute("pangea.recipe", recipe);
            if (toolName != null) {
                policySpan.setAttribute("pangea.tool_name", toolName);
            }
            policySpan.setAttribute("pangea.text_length", text != null ? text.length() : 0);

            log.info("Mock Pangea policy evaluation: {} {} {} {}",
                    kv("recipe", recipe),
                    kv("toolName", toolName),
                    kv("textLength", text != null ? text.length() : 0),
                    kv("decision", "allow"));

            GuardDecision decision = new GuardDecision(recipe, false, null);

            policySpan.setAttribute("pangea.blocked", decision.blocked());
            return decision;
        } finally {
            policySpan.end();
        }
    }
}
