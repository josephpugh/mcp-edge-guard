package com.edwardjones.mcp.edge.policy;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MockPangeaGuardClientTest {

    @RegisterExtension
    static final OpenTelemetryExtension otelExtension = OpenTelemetryExtension.create();

    @Test
    void guardAllowsAndRecordsPolicySpanAttributes() {
        MockPangeaGuardClient client = new MockPangeaGuardClient(
                otelExtension.getOpenTelemetry().getTracer("mock-pangea-test"));

        GuardDecision decision = client.guard("pangea_agent_pre_tool_guard", "hello world", "search_docs");

        assertEquals("pangea_agent_pre_tool_guard", decision.recipe());
        assertFalse(decision.blocked());
        assertNull(decision.code());

        List<SpanData> spans = otelExtension.getSpans();
        SpanData policySpan = spans.stream()
                .filter(s -> s.getName().equals("pangea.policy.evaluate"))
                .findFirst()
                .orElseThrow();

        assertEquals("pangea_agent_pre_tool_guard", policySpan.getAttributes().get(
                AttributeKey.stringKey("pangea.recipe")));
        assertEquals("search_docs", policySpan.getAttributes().get(
                AttributeKey.stringKey("pangea.tool_name")));
        assertEquals(11L, policySpan.getAttributes().get(
                AttributeKey.longKey("pangea.text_length")));
        assertEquals(false, policySpan.getAttributes().get(
                AttributeKey.booleanKey("pangea.blocked")));
    }

    @Test
    void guardHandlesNullTextAndToolName() {
        MockPangeaGuardClient client = new MockPangeaGuardClient(
                otelExtension.getOpenTelemetry().getTracer("mock-pangea-test"));

        GuardDecision decision = client.guard("pangea_prompt_guard", null, null);

        assertEquals("pangea_prompt_guard", decision.recipe());
        assertFalse(decision.blocked());

        List<SpanData> spans = otelExtension.getSpans();
        SpanData policySpan = spans.stream()
                .filter(s -> s.getName().equals("pangea.policy.evaluate"))
                .findFirst()
                .orElseThrow();

        assertEquals(0L, policySpan.getAttributes().get(
                AttributeKey.longKey("pangea.text_length")));
        assertNull(policySpan.getAttributes().get(
                AttributeKey.stringKey("pangea.tool_name")));
    }

    @Test
    void guardAllowsWithDefaultConstructor() {
        MockPangeaGuardClient client = new MockPangeaGuardClient();

        GuardDecision decision = client.guard("pangea_prompt_guard", "hello", null);

        assertEquals("pangea_prompt_guard", decision.recipe());
        assertFalse(decision.blocked());
        assertNull(decision.code());
    }
}
