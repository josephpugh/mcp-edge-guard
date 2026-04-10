package com.edwardjones.mcp.edge.grpc;

import io.envoyproxy.envoy.config.core.v3.HeaderMap;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HeaderUtilTest {

    @Test
    void toMapConvertsHeaders() {
        HeaderMap headerMap = HeaderMap.newBuilder()
                .addHeaders(HeaderValue.newBuilder().setKey(":method").setValue("POST").build())
                .addHeaders(HeaderValue.newBuilder().setKey("Content-Type").setValue("application/json").build())
                .addHeaders(HeaderValue.newBuilder().setKey("traceparent").setValue("00-abc-def-01").build())
                .build();

        Map<String, String> result = HeaderUtil.toMap(headerMap);

        assertEquals(3, result.size());
        assertEquals("POST", result.get(":method"));
        assertEquals("application/json", result.get("content-type")); // lowercased
        assertEquals("00-abc-def-01", result.get("traceparent"));
    }

    @Test
    void toMapHandlesNull() {
        Map<String, String> result = HeaderUtil.toMap(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void toMapHandlesEmptyHeaderMap() {
        HeaderMap headerMap = HeaderMap.getDefaultInstance();
        Map<String, String> result = HeaderUtil.toMap(headerMap);
        assertTrue(result.isEmpty());
    }

    @Test
    void toMapLowercasesKeys() {
        HeaderMap headerMap = HeaderMap.newBuilder()
                .addHeaders(HeaderValue.newBuilder().setKey("X-Custom-Header").setValue("value").build())
                .build();

        Map<String, String> result = HeaderUtil.toMap(headerMap);

        assertNull(result.get("X-Custom-Header"));
        assertEquals("value", result.get("x-custom-header"));
    }
}
