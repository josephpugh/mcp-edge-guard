package com.edwardjones.mcp.edge.grpc;

import io.envoyproxy.envoy.config.core.v3.HeaderMap;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class HeaderUtil {

    public static Map<String, String> toMap(HeaderMap headerMap) {
        Map<String, String> map = new LinkedHashMap<>();
        if (headerMap == null) {
            return map;
        }
        for (HeaderValue hv : headerMap.getHeadersList()) {
            map.put(hv.getKey().toLowerCase(Locale.ROOT), hv.getValue());
        }
        return map;
    }

    private HeaderUtil() {
    }
}
