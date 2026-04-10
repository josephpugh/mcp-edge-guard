package com.edwardjones.mcp.edge.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpEnvelopeTest {

    @Test
    void parseToolsCall() throws Exception {
        String json = """
                {
                  "jsonrpc": "2.0",
                  "id": "42",
                  "method": "tools/call",
                  "params": {
                    "name": "get_weather",
                    "arguments": {"city": "Chicago"}
                  }
                }
                """;

        McpEnvelope env = McpEnvelope.parse(json);

        assertEquals("42", env.id());
        assertEquals("tools/call", env.method());
        assertEquals("get_weather", env.toolName());
        assertNotNull(env.policyText());
        assertTrue(env.policyText().contains("get_weather"));
    }

    @Test
    void parseNonToolMethod() throws Exception {
        String json = """
                {
                  "jsonrpc": "2.0",
                  "id": "1",
                  "method": "resources/list"
                }
                """;

        McpEnvelope env = McpEnvelope.parse(json);

        assertEquals("1", env.id());
        assertEquals("resources/list", env.method());
        assertNull(env.toolName());
    }

    @Test
    void parseBatchArray() throws Exception {
        String json = """
                [
                  {
                    "jsonrpc": "2.0",
                    "id": "10",
                    "method": "tools/call",
                    "params": {"name": "lookup_user"}
                  }
                ]
                """;

        McpEnvelope env = McpEnvelope.parse(json);

        assertEquals("10", env.id());
        assertEquals("tools/call", env.method());
        assertEquals("lookup_user", env.toolName());
    }

    @Test
    void parseMissingId() throws Exception {
        String json = """
                {"method": "notifications/initialized"}
                """;

        McpEnvelope env = McpEnvelope.parse(json);

        assertEquals("null", env.id());
        assertEquals("notifications/initialized", env.method());
        assertNull(env.toolName());
    }

    @Test
    void parseMissingMethod() throws Exception {
        String json = """
                {"jsonrpc": "2.0", "id": "5", "result": {"tools": []}}
                """;

        McpEnvelope env = McpEnvelope.parse(json);

        assertEquals("5", env.id());
        assertNull(env.method());
        assertNull(env.toolName());
    }

    @Test
    void truncatesLargePolicyText() throws Exception {
        StringBuilder sb = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"tools/call\",\"params\":{\"name\":\"big_tool\",\"arguments\":{\"data\":\"");
        while (sb.length() < McpEnvelope.MAX_POLICY_TEXT_LENGTH + 1000) {
            sb.append("x");
        }
        sb.append("\"}}}");

        McpEnvelope env = McpEnvelope.parse(sb.toString());

        assertNotNull(env.policyText());
        assertEquals(McpEnvelope.MAX_POLICY_TEXT_LENGTH, env.policyText().length());
    }

    @Test
    void parseNumericId() throws Exception {
        String json = """
                {"jsonrpc": "2.0", "id": 99, "method": "tools/list"}
                """;

        McpEnvelope env = McpEnvelope.parse(json);

        assertEquals("99", env.id());
        assertEquals("tools/list", env.method());
    }

    @Test
    void parseToolsCallWithoutToolName() throws Exception {
        String json = """
                {
                  "jsonrpc": "2.0",
                  "id": "1",
                  "method": "tools/call",
                  "params": {}
                }
                """;

        McpEnvelope env = McpEnvelope.parse(json);

        assertEquals("tools/call", env.method());
        assertNull(env.toolName());
    }
}
