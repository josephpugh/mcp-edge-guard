package com.edwardjones.mcp.edge.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.logstash.logback.argument.StructuredArguments.kv;

public record McpEnvelope(String id, String idJson, String method, String toolName, String policyText) {

    private static final Logger log = LoggerFactory.getLogger(McpEnvelope.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final int MAX_POLICY_TEXT_LENGTH = 20_000;

    public static McpEnvelope parse(String rawBody) throws Exception {
        JsonNode root = MAPPER.readTree(rawBody);

        boolean isBatch = root.isArray() && !root.isEmpty();
        JsonNode msg = isBatch ? representativeBatchMessage(root) : root;

        JsonNode idNode = msg.get("id");
        String id = idNode != null ? idNode.asText("null") : "null";
        String idJson = idNode != null ? MAPPER.writeValueAsString(idNode) : "null";
        String method = msg.path("method").asText(null);
        String toolName = "tools/call".equals(method)
                ? msg.path("params").path("name").asText(null)
                : null;

        String policyText = MAPPER.writeValueAsString(isBatch ? root : msg);
        boolean truncated = policyText.length() > MAX_POLICY_TEXT_LENGTH;
        if (truncated) {
            policyText = policyText.substring(0, MAX_POLICY_TEXT_LENGTH);
        }

        log.debug("Parsed MCP envelope: {} {} {} {} {}",
                kv("requestId", id),
                kv("method", method),
                kv("toolName", toolName),
                kv("isBatch", isBatch),
                kv("policyTextTruncated", truncated));

        return new McpEnvelope(id, idJson, method, toolName, policyText);
    }

    private static JsonNode representativeBatchMessage(JsonNode batch) {
        for (JsonNode msg : batch) {
            if ("tools/call".equals(msg.path("method").asText(null))) {
                return msg;
            }
        }
        return batch.get(0);
    }
}
