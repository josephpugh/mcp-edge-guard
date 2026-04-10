# mcp-edge-guard

A gRPC External Processing service that acts as a centralized policy enforcement point for MCP (Model Context Protocol) server interactions mediated by MuleSoft Flex Gateway.

## What it does

Flex Gateway routes all MCP API traffic through this service via its [External Processing](https://docs.mulesoft.com/gateway/latest/policies-included-external-processing) policy. The service inspects both request and response bodies, enforces security policies via CrowdStrike Pangea AI Guard, and produces OpenTelemetry spans that give end-to-end visibility across the gateway, this processor, and the upstream MCP server.

### Request flow

```
Client -> Flex Gateway -> mcp-edge-guard -> MCP Server
                |                |
          gateway span    mcp.edge.guard span
                               |
                    pangea.request.policy span
                    pangea.response.policy span
```

1. **Request headers** -- Extracts W3C trace context and baggage from incoming headers. Creates the `mcp.edge.guard` parent span and injects its trace context back into request headers so the upstream MCP server's spans become children of it.
2. **Request body** -- Parses the MCP JSON-RPC envelope to extract method, tool name, and request ID. Evaluates the request against Pangea policy. Blocks with a JSON-RPC error if the policy rejects it.
3. **Response headers** -- Passed through.
4. **Response body** -- Evaluates the upstream response against Pangea policy. Replaces the body with a JSON-RPC error if rejected.

### Trace propagation

The processor creates a single `mcp.edge.guard` span that starts when request headers arrive and ends when the stream completes. By injecting this span's context into the forwarded request headers, any spans created by the MCP server automatically become children of `mcp.edge.guard`, giving a unified trace across all three layers.

Allowlisted OpenTelemetry Baggage keys (`tenant.id`, `user.id`, `session.id`) are copied onto the guard span as attributes.

### Span attributes

| Attribute | Source |
|---|---|
| `mcp.method` | JSON-RPC `method` field |
| `mcp.tool.name` | `params.name` for `tools/call` |
| `rpc.system` | `jsonrpc` |
| `edge.component` | `mcp-edge-guard` |
| `baggage.*` | Allowlisted baggage keys |
| `security.blocked` | Whether the request/response was blocked |
| `security.phase` | `request` or `response` |
| `security.code` | Policy violation code |

## Project structure

```
src/main/java/com/edwardjones/mcp/edge/
  grpc/
    McpEdgeExternalProcessor   # gRPC ext-proc service implementation
    ResponseBuilder            # Builds ext-proc response messages
    HeaderUtil                 # Envoy HeaderMap conversion
    SessionState               # Per-stream state (span, MCP metadata)
  trace/
    TraceUtil                  # W3C context extraction/injection, baggage handling
  mcp/
    McpEnvelope                # MCP JSON-RPC body parser
  policy/
    PangeaGuardClient          # Policy evaluation interface
    MockPangeaGuardClient      # Mock implementation (always allows)
    GuardDecision              # Policy decision record

src/main/resources/
  application.yaml             # Spring Boot + gRPC server config
  flex-policy/                 # Reference Flex Gateway policy definitions
```

## Building and running

```bash
./gradlew build        # compile + test
./gradlew bootRun      # start on gRPC port 9002
```

### Requirements

- Java 21+
- Gradle 8.12+

### OpenTelemetry

The service depends only on the OpenTelemetry API. In production, Dynatrace OneAgent attaches at runtime and provides the SDK implementation -- no exporters are configured in-app. For local development, spans are no-ops unless an SDK is registered.

## Flex Gateway integration

Reference policy definitions are in `src/main/resources/flex-policy/`. Apply them as API-level policies in this order:

1. MCP Support
2. Authentication / Authorization
3. MCP Schema Validation
4. Tracing (`mcp-tracing-policy.yaml`)
5. External Processing (`mcp-edge-guard-policy.yaml`)

Key policy settings: `failureModeAllow=false` (fail closed), `observabilityMode=false` (blocking mode), buffered request/response bodies.

## Replacing the mock Pangea client

`MockPangeaGuardClient` always returns an allow decision. To integrate with Pangea AI Guard, implement `PangeaGuardClient` and call the `/v1/text/guard` endpoint with the appropriate recipe (`pangea_agent_pre_tool_guard`, `pangea_prompt_guard`, or `pangea_agent_post_tool_guard`). Note the 20 KiB input limit on AI Guard -- the `McpEnvelope` parser already truncates policy text to that size.
