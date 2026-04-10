# Copilot Instructions

This repository is `mcp-edge-guard`, a Java 21 Spring Boot gRPC External Processing service for MuleSoft Flex Gateway. It inspects MCP JSON-RPC request and response bodies, evaluates them through a Pangea guard client abstraction, and creates OpenTelemetry spans for gateway, guard, and policy evaluation flows.

## Project Shape

- Main package: `com.edwardjones.mcp.edge`.
- gRPC processing code lives in `src/main/java/com/edwardjones/mcp/edge/grpc`.
- MCP JSON-RPC parsing lives in `src/main/java/com/edwardjones/mcp/edge/mcp`.
- Policy client abstractions live in `src/main/java/com/edwardjones/mcp/edge/policy`.
- OpenTelemetry helpers live in `src/main/java/com/edwardjones/mcp/edge/trace`.
- Local test-only REST support lives under `src/main/java/com/edwardjones/mcp/edge/test` and must remain local-profile only.
- Tests mirror the production package layout under `src/test/java`.

## Build And Test

- Use Gradle through the wrapper: `./gradlew check`.
- Tests use JUnit 5, gRPC in-process test utilities, and `OpenTelemetryExtension`.
- Keep tests focused on behavior and use the existing assertion style in nearby tests.
- `check` includes JaCoCo coverage verification; do not remove the coverage gate to make tests pass.
- Prefer adding regression tests whenever changing Envoy response shape, MCP parsing, tracing, or policy behavior.

## Implementation Guidelines

- Keep changes small and aligned with the existing package boundaries.
- Prefer Jackson `ObjectMapper`/`JsonNode` for JSON parsing or serialization. Do not hand-build JSON with string concatenation or `String.format`.
- Preserve JSON-RPC ID shape. String IDs must remain JSON strings, numeric IDs must remain numbers, and missing IDs should be `null` in generated JSON-RPC error bodies.
- Use Envoy ext-proc response types deliberately:
  - Use `ImmediateResponse` for request-phase blocking before the upstream MCP server has responded.
  - Use `CONTINUE_AND_REPLACE` body mutations for response-phase blocking.
  - Use `CONTINUE` for passthrough request and response phases.
- Normalize HTTP/header names with `Locale.ROOT`.
- Do not log full request or response bodies, secrets, or unrestricted baggage. Prefer structured logging via `StructuredArguments.kv`.

## Tracing And Baggage

- `mcp.edge.guard` is the long-lived span for an ext-proc stream.
- Policy spans are named `pangea.policy.evaluate`.
- Use `TraceUtil.copyAllowedBaggageToSpan` whenever copying baggage-derived span attributes.
- Only these baggage keys may become span attributes: `tenant.id`, `user.id`, `session.id`.
- Do not copy arbitrary baggage keys into spans, logs, policy requests, or error bodies.
- Keep policy evaluation running under the full guard context, not just the guard span, so baggage remains available to policy spans.
- Production should rely on the OpenTelemetry API and external runtime instrumentation. The local SDK setup belongs behind the `local` Spring profile.

## Policy Behavior

- `PangeaGuardClient` is the abstraction for policy evaluation.
- `MockPangeaGuardClient` is a local/mock implementation and should continue to allow by default unless a test explicitly overrides behavior.
- Request tool calls use `pangea_agent_pre_tool_guard`.
- Non-tool request methods use `pangea_prompt_guard`.
- Response bodies use `pangea_agent_post_tool_guard`.
- Preserve fail-closed behavior for unexpected inspection failures unless there is an explicit product decision to change it.

## Local Test Controller

- `ExtProcTestController` is only for local simulation of the Flex Gateway ext-proc flow.
- Keep it under `@Profile("local")`; do not expose `/test/evaluate` in default or production profiles.
- When changing the controller result shape, update `ExtProcTestControllerTest`.

## Style

- Follow existing Java formatting and keep comments sparse.
- Avoid broad refactors unless they directly support the requested change.
- Prefer package-private constructors/helpers where tests already use that pattern.
- Keep user-facing error messages stable unless tests and callers are updated together.
