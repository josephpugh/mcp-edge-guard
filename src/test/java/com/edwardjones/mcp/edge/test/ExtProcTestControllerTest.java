package com.edwardjones.mcp.edge.test;

import com.edwardjones.mcp.edge.grpc.McpEdgeExternalProcessor;
import com.edwardjones.mcp.edge.policy.GuardDecision;
import com.edwardjones.mcp.edge.policy.PangeaGuardClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExtProcTestControllerTest {

    @Test
    void evaluateReturnsCompletedAllowedResult() throws Exception {
        ExtProcTestController controller = controllerWith((recipe, text, toolName) ->
                new GuardDecision(recipe, false, null));

        ResponseEntity<Map<String, Object>> response = controller.evaluate(
                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"tools/call\",\"params\":{\"name\":\"search_docs\"}}",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
                "tenant.id=acme",
                null);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("completed", body.get("stoppedAt"));
        assertEquals(true, body.get("allowed"));

        List<Map<String, Object>> phases = phases(body);
        assertEquals(4, phases.size());
        assertEquals("request_headers", phases.get(0).get("phase"));
        assertEquals("request_body", phases.get(1).get("phase"));
        assertEquals("response_headers", phases.get(2).get("phase"));
        assertEquals("response_body", phases.get(3).get("phase"));
    }

    @Test
    void evaluateStopsAtRequestBodyWhenRequestIsBlocked() throws Exception {
        ExtProcTestController controller = controllerWith((recipe, text, toolName) -> {
            if (recipe.equals("pangea_agent_pre_tool_guard")) {
                return new GuardDecision(recipe, true, "PII_DETECTED");
            }
            return new GuardDecision(recipe, false, null);
        });

        ResponseEntity<Map<String, Object>> response = controller.evaluate(
                "{\"jsonrpc\":\"2.0\",\"id\":\"42\",\"method\":\"tools/call\",\"params\":{\"name\":\"get_ssn\"}}",
                null,
                null,
                null);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("request_body", body.get("stoppedAt"));
        assertEquals(false, body.get("allowed"));

        List<Map<String, Object>> phases = phases(body);
        assertEquals(2, phases.size());
        assertEquals("immediate_response", phases.get(1).get("phase"));
        assertEquals("blocked", phases.get(1).get("action"));
        assertTrue(phases.get(1).get("body").toString().contains("PII_DETECTED"));
    }

    @Test
    void evaluateReportsReplacedResponseBodyWhenResponseIsBlocked() throws Exception {
        ExtProcTestController controller = controllerWith((recipe, text, toolName) -> {
            if (recipe.equals("pangea_agent_post_tool_guard")) {
                return new GuardDecision(recipe, true, "MALICIOUS_CONTENT");
            }
            return new GuardDecision(recipe, false, null);
        });

        ResponseEntity<Map<String, Object>> response = controller.evaluate(
                "{\"jsonrpc\":\"2.0\",\"id\":\"5\",\"method\":\"tools/call\",\"params\":{\"name\":\"query_db\"}}",
                null,
                null,
                "{\"jsonrpc\":\"2.0\",\"id\":\"5\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"bad\"}]}}");

        assertTrue(response.getStatusCode().is2xxSuccessful());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("completed", body.get("stoppedAt"));
        assertEquals(false, body.get("allowed"));

        List<Map<String, Object>> phases = phases(body);
        assertEquals(4, phases.size());
        assertEquals("response_body", phases.get(3).get("phase"));
        assertEquals("continue_and_replace", phases.get(3).get("action"));
        assertTrue(phases.get(3).get("replacedBody").toString().contains("MALICIOUS_CONTENT"));
    }

    private ExtProcTestController controllerWith(PangeaGuardClient guardClient) {
        return new ExtProcTestController(new McpEdgeExternalProcessor(guardClient));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> phases(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("phases");
    }
}
