package com.edwardjones.mcp.edge.policy;

public interface PangeaGuardClient {
    GuardDecision guard(String recipe, String text, String toolName);
}
