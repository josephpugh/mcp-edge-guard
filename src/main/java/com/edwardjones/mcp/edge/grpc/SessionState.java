package com.edwardjones.mcp.edge.grpc;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;

class SessionState {
    Context guardContext;
    Span guardSpan;
    String requestId;
    String requestIdJson;
    String mcpMethod;
    String toolName;
}
