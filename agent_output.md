## Recommendation

Use a **centralized hybrid edge pattern**:

1. **Flex Gateway remains the policy enforcement point** for every MCP API instance.
2. A single shared **External Processing** service in AKS handles the parts Flex cannot do cleanly by itself: body-aware extraction, baggage-to-span enrichment, and request/response policy checks against CrowdStrike Pangea.
3. **Flex exports gateway spans via OpenTelemetry**, while the helper service and upstream MCP apps rely on **Dynatrace OneAgent** for application-side span capture.

That gives you a reusable standard with **zero per-MCP-server code** while still keeping the control point at MuleSoft. The reason I would not force this into “Flex-only” is that Flex tracing can export OTLP, but its tracing labels are limited to `literal`, `environment`, and `requestHeader` sources, not request-body fields or per-baggage-key parsing; and Mule’s PDK body-processing model has header/body ordering and body-size limitations that make this awkward for a reusable request/response envelope. External Processing is the supported mechanism specifically built to inspect and modify both requests and responses, and to short-circuit immediately when needed. ([MuleSoft Documentation][1])

## The key MuleSoft nuance that changes the rollout design

For **MCP** specifically, do **not** use Flex **automated policies** as your standardization mechanism. The reason is subtle but important:

* **MCP Support** must be **first** in the policy order.
* MCP/agent policies are **not supported as automated policies**.
* Automated policies run **before** API-level policies.

Those three facts together mean the safe standardization pattern is **API-manager provisioning automation** that creates each MCP API instance and attaches a fixed **API-level** policy bundle in the right order. That still keeps teams out of custom code, but the rollout vehicle is an onboarding pipeline or internal registration service, not environment-wide automated policies. MuleSoft’s API Manager API and CLI are suitable for that provisioning workflow. ([MuleSoft Documentation][2])

## Recommended target architecture

The standardized stack on each MCP API instance should be:

1. **MCP Support** policy
2. Your normal **authentication / authorization** controls
3. **MCP Schema Validation**
4. **Tracing** policy override for the API
5. **External Processing** policy pointing to one shared AKS service, e.g. `mcp-edge-guard`

That ordering gives you cheap rejection of malformed MCP calls before expensive security checks, while keeping the helper service as the centralized place for payload-aware logic. MCP Schema Validation can validate MCP JSON-RPC structure and tool invocation arguments; External Processing can observe request and response bodies, request more body data if needed, mutate content, or immediately return a downstream response without calling the upstream server. Keep `observabilityMode=false`, because you need blocking and mutation, not mirror-only behavior. ([MuleSoft Documentation][3])

## Why the helper service is necessary

With current MCP transport, the **tool name is in the JSON-RPC body**, not in an HTTP path segment or standard header. In a `tools/call`, the MCP method is `tools/call` and the tool name is in `params.name`. Also, Streamable HTTP MCP uses a single POST endpoint and may return either `application/json` or `text/event-stream`. That means any edge solution that wants to tag spans with tool names or inspect tool inputs/outputs must be able to read the body, and for responses it must handle both normal JSON and possible SSE streams. ([Model Context Protocol][4])

## How I would model the spans

Use a **two-layer span model**:

* The **Flex gateway span** is the outer envelope for the HTTP/MCP interaction.
* The helper service creates one semantic child span, e.g. **`mcp.edge.guard`**, that starts when the first request headers arrive and ends when the final response body/trailers are processed.
* Under that wrapper, create **one child span per Pangea evaluation**:

  * `pangea.request.policy`
  * `pangea.response.policy`
  * or one child span per recipe if you truly make multiple callouts

Put the rich attributes on **`mcp.edge.guard`**:

* `mcp.method`
* `mcp.tool.name`
* selected baggage copies
* policy verdicts
* block phase (`request` / `response`)
* error code returned to the client

This is the cleanest way to satisfy your “single span wrapping the whole request/response” requirement without touching each MCP server. I would be explicit, though: **current Flex tracing does not natively give you a way to parse individual baggage members or request-body fields directly into the gateway span itself**. Tracing labels are header/env/literal only. If you absolutely require those body-derived fields on the **outer Flex span itself**, that becomes a custom filter/WASM-style problem and is not the maintainable option here. ([MuleSoft Documentation][5])

## Trace propagation and Dynatrace behavior

Flex 1.11.4 ships Envoy 1.35.3, and Envoy 1.31+ fixed tracing-context propagation on bidirectional gRPC streams while Envoy 1.33 added ext-proc span sampling inheritance. Based on that, the helper service **should** receive usable trace context for correlation with the gateway trace. I would still code a defensive fallback that extracts `traceparent`, `tracestate`, and `baggage` from the original HTTP headers contained in the ext-proc request message. ([MuleSoft Documentation][6])

On the Dynatrace side, the big rule is: **do not OTLP-export spans from the helper service if OneAgent is attached there**. Dynatrace documents that OneAgent automatically detects OpenTelemetry spans and captures their attributes; exporting the same spans again to OTLP can create duplicates. So: Flex exports gateway spans; helper service and MCP apps rely on OneAgent span capture. ([Dynatrace Documentation][7])

## Baggage handling

OpenTelemetry treats **baggage** separately from **span attributes**, so copying baggage onto spans requires explicit code or explicit SDK configuration. In the helper service, copy only an **allowlist** of keys such as `tenant.id`, `user.id`, or `session.id` onto `mcp.edge.guard` and optionally onto Pangea child spans as well. Do **not** put secrets or sensitive content into baggage, because baggage propagates in headers. If you want a zero-code helper-side option, OpenTelemetry Java contrib provides `BaggageSpanProcessor` and the property `otel.java.experimental.span-attributes.copy-from-baggage.include=...`. ([OpenTelemetry][8])

## How to use CrowdStrike Pangea

For your use case, I would center this on **Pangea AI Guard** rather than a narrower single-purpose detector:

* **Request side**

  * `pangea_agent_pre_tool_guard` for `tools/call`
  * optionally `pangea_prompt_guard` for prompt-like content outside tool calls
* **Response side**

  * `pangea_agent_post_tool_guard` for tool output
  * optionally `pangea_llm_response_guard` if the MCP server emits LLM-generated content you also want checked

AI Guard accepts text or message arrays, returns whether content was blocked, and can also return transformed output. It also supports `log_fields` including items such as `tools`, `source`, and `model`. One very important operational limit: the documented input size is currently **20 KiB**, so for large responses or SSE you need an explicit strategy: canonicalize/truncate, inspect per completed event, or fall back to block/allow on metadata only. ([Pangea][9])

## Response-mode recommendation

For a first implementation:

* **Request body**: `buffered`
* **Response body**: `buffered` for normal JSON, or `streamed` / `bufferedPartial` when an MCP server can return SSE or larger payloads

That recommendation follows from MCP’s Streamable HTTP transport and Envoy ext-proc’s body modes. If you mutate bodies in streamed or partial modes, pay attention to content-length behavior. As an optimization, ext-proc can start with headers and then request body content later using mode override, but I would start with the simpler buffered design first unless latency is already tight. ([Model Context Protocol][4])

---

## Reference configuration

### 1) Flex global tracing

Use Flex’s OpenTelemetry exporter globally. Whether you send that to a collector or directly to Dynatrace OTLP is an implementation choice.

```yaml
apiVersion: gateway.mulesoft.com/v1beta1
kind: Configuration
metadata:
  name: tracing
spec:
  tracing:
    provider:
      type: opentelemetry
      properties:
        serviceName: flex-mcp-edge
        serviceType: grpc
        address: h2://otel-collector.observability.svc.cluster.local:4317
        timeout: 10s
        resourceAttributes:
          deployment.environment: prod
          gateway.role: mcp-edge
    sampling:
      client: 100
      random: 100
      overall: 100
```

Flex supports OpenTelemetry tracing for API traffic and OTLP export from the gateway. ([MuleSoft Documentation][10])

### 2) API-level tracing policy override

Only use this for what Flex can natively set on the gateway span: span name and header/env/literal labels.

```yaml
# illustrative policy config
policyRef:
  name: tracing
config:
  spanName: mcp.edge
  sampling:
    client: 100
    random: 100
    overall: 100
  labels:
    - name: mcp.protocol.version
      type: requestHeader
      keyName: mcp-protocol-version
      defaultValue: ""
    - name: client.origin
      type: requestHeader
      keyName: origin
      defaultValue: ""
    - name: gateway.cluster
      type: environment
      keyName: CLUSTER_NAME
      defaultValue: "unknown"
```

The important limit is that these labels cannot be sourced from the request body or parsed baggage members. ([MuleSoft Documentation][5])

### 3) External Processing policy

```yaml
# illustrative policy config
policyRef:
  name: external-processing
config:
  uri: h2://mcp-edge-guard.guardrails.svc.cluster.local:9002
  messageTimeout: 2000
  maxMessageTimeout: 5000
  failureModeAllow: false
  observabilityMode: false
  allowModeOverride: true

  requestHeaderMode: send
  requestBodyMode: buffered

  responseHeaderMode: send
  # choose one of:
  responseBodyMode: buffered
  # responseBodyMode: bufferedPartial
  # responseBodyMode: streamed
```

`failureModeAllow=false` is the right default for a security control. `observabilityMode=false` is required because you need a blocking decision path. ([MuleSoft Documentation][1])

---

## Reference helper implementation

Below is an illustrative Java/Spring Boot-style ext-proc service skeleton. I would implement this as a small internal service, not as per-team MCP code.

### 1) Trace extraction and baggage copy

```java
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;

import java.util.Map;
import java.util.Set;

public final class TraceUtil {
  private static final Set<String> BAGGAGE_ALLOWLIST =
      Set.of("tenant.id", "user.id", "session.id");

  private static final TextMapGetter<Map<String, String>> GETTER =
      new TextMapGetter<>() {
        @Override public Iterable<String> keys(Map<String, String> carrier) { return carrier.keySet(); }
        @Override public String get(Map<String, String> carrier, String key) { return carrier.get(key); }
      };

  public static Context extractParent(Map<String, String> headers) {
    Context ctx = W3CTraceContextPropagator.getInstance()
        .extract(Context.root(), headers, GETTER);
    return W3CBaggagePropagator.getInstance()
        .extract(ctx, headers, GETTER);
  }

  public static void copyAllowedBaggageToSpan(Span span, Context ctx) {
    Baggage.fromContext(ctx).forEach((key, entry) -> {
      if (BAGGAGE_ALLOWLIST.contains(key)) {
        span.setAttribute("baggage." + key, entry.getValue());
      }
    });
  }

  private TraceUtil() {}
}
```

### 2) MCP body parsing

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public record McpEnvelope(String id, String method, String toolName, String policyText) {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static McpEnvelope parse(String rawBody) throws Exception {
    JsonNode root = MAPPER.readTree(rawBody);

    // Defensive support for older batched JSON-RPC transports
    JsonNode msg = root.isArray() && root.size() > 0 ? root.get(0) : root;

    String id = msg.has("id") ? msg.get("id").toString() : "null";
    String method = msg.path("method").asText(null);
    String toolName = "tools/call".equals(method)
        ? msg.path("params").path("name").asText(null)
        : null;

    // Canonicalize for policy evaluation; do not ship arbitrary huge JSON blobs
    JsonNode policyNode = msg.deepCopy();
    String policyText = MAPPER.writeValueAsString(policyNode);
    if (policyText.length() > 20_000) {
      policyText = policyText.substring(0, 20_000);
    }

    return new McpEnvelope(id, method, toolName, policyText);
  }
}
```

### 3) External processor skeleton

```java
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

public class McpEdgeExternalProcessor extends ExternalProcessorGrpc.ExternalProcessorImplBase {

  private static final Tracer TRACER =
      GlobalOpenTelemetry.getTracer("mcp-edge-guard");

  private final PangeaGuardClient pangea;

  public McpEdgeExternalProcessor(PangeaGuardClient pangea) {
    this.pangea = pangea;
  }

  @Override
  public StreamObserver<ProcessingRequest> process(
      StreamObserver<ProcessingResponse> out) {

    return new StreamObserver<>() {
      final SessionState s = new SessionState();

      @Override
      public void onNext(ProcessingRequest in) {
        switch (in.getRequestCase()) {
          case REQUEST_HEADERS -> handleRequestHeaders(in.getRequestHeaders(), s, out);
          case REQUEST_BODY -> handleRequestBody(in.getRequestBody(), s, out);
          case RESPONSE_HEADERS -> out.onNext(Responses.continueResponseHeaders());
          case RESPONSE_BODY -> handleResponseBody(in.getResponseBody(), s, out);
          default -> out.onNext(Responses.continueWithoutChanges());
        }
      }

      @Override
      public void onError(Throwable t) {
        if (s.guardSpan != null) {
          s.guardSpan.recordException(t);
          s.guardSpan.setStatus(StatusCode.ERROR, t.getMessage());
          s.guardSpan.end();
        }
      }

      @Override
      public void onCompleted() {
        if (s.guardSpan != null) {
          s.guardSpan.end();
        }
        out.onCompleted();
      }
    };
  }

  private void handleRequestHeaders(RequestHeaders req, SessionState s,
                                    StreamObserver<ProcessingResponse> out) {
    var headers = HeaderUtil.toMap(req.getHeaders());
    Context parent = TraceUtil.extractParent(headers);

    s.guardContext = parent;
    s.guardSpan = TRACER.spanBuilder("mcp.edge.guard")
        .setParent(parent)
        .setSpanKind(SpanKind.INTERNAL)
        .startSpan();

    TraceUtil.copyAllowedBaggageToSpan(s.guardSpan, parent);
    s.guardSpan.setAttribute("edge.component", "mcp-edge-guard");
    out.onNext(Responses.continueRequestHeaders());
  }

  private void handleRequestBody(RequestBody bodyMsg, SessionState s,
                                 StreamObserver<ProcessingResponse> out) {
    try (Scope ignored = s.guardSpan.makeCurrent()) {
      String raw = bodyMsg.getBody().toStringUtf8();
      McpEnvelope env = McpEnvelope.parse(raw);

      s.requestId = env.id();
      s.mcpMethod = env.method();
      s.toolName = env.toolName();

      if (env.method() != null) s.guardSpan.setAttribute("mcp.method", env.method());
      if (env.toolName() != null) s.guardSpan.setAttribute("mcp.tool.name", env.toolName());
      s.guardSpan.setAttribute("rpc.system", "jsonrpc");

      Span policySpan = TRACER.spanBuilder("pangea.request.policy")
          .setParent(Context.current())
          .startSpan();

      GuardDecision decision;
      try (Scope policyScope = policySpan.makeCurrent()) {
        decision = pangea.guard(
            "tools/call".equals(env.method()) ? "pangea_agent_pre_tool_guard"
                                              : "pangea_prompt_guard",
            env.policyText(),
            s.toolName
        );
        policySpan.setAttribute("pangea.recipe", decision.recipe());
        policySpan.setAttribute("security.blocked", decision.blocked());
        if (decision.code() != null) {
          policySpan.setAttribute("security.code", decision.code());
        }
      } finally {
        policySpan.end();
      }

      if (decision.blocked()) {
        s.guardSpan.setAttribute("security.blocked", true);
        s.guardSpan.setAttribute("security.phase", "request");
        s.guardSpan.setAttribute("security.code", decision.code());
        s.guardSpan.setStatus(StatusCode.ERROR, "request blocked");

        out.onNext(
            Responses.immediateJsonRpcError(
                s.requestId,
                -32001,
                "Blocked by security policy",
                decision.code()
            )
        );
        return;
      }

      out.onNext(Responses.continueRequestBody());
    } catch (Exception e) {
      s.guardSpan.recordException(e);
      s.guardSpan.setStatus(StatusCode.ERROR, "request inspection failed");
      out.onNext(Responses.immediateHttpError(500, "security inspection failure"));
    }
  }

  private void handleResponseBody(ResponseBody bodyMsg, SessionState s,
                                  StreamObserver<ProcessingResponse> out) {
    try (Scope ignored = s.guardSpan.makeCurrent()) {
      String raw = bodyMsg.getBody().toStringUtf8();

      Span policySpan = TRACER.spanBuilder("pangea.response.policy")
          .setParent(Context.current())
          .startSpan();

      GuardDecision decision;
      try (Scope policyScope = policySpan.makeCurrent()) {
        decision = pangea.guard("pangea_agent_post_tool_guard", raw, s.toolName);
        policySpan.setAttribute("pangea.recipe", decision.recipe());
        policySpan.setAttribute("security.blocked", decision.blocked());
        if (decision.code() != null) {
          policySpan.setAttribute("security.code", decision.code());
        }
      } finally {
        policySpan.end();
      }

      if (decision.blocked()) {
        s.guardSpan.setAttribute("security.blocked", true);
        s.guardSpan.setAttribute("security.phase", "response");
        s.guardSpan.setAttribute("security.code", decision.code());
        s.guardSpan.setStatus(StatusCode.ERROR, "response blocked");

        out.onNext(
            Responses.replaceResponseWithJsonRpcError(
                s.requestId,
                -32002,
                "Response blocked by security policy",
                decision.code()
            )
        );
        return;
      }

      out.onNext(Responses.continueResponseBody());
    } catch (Exception e) {
      s.guardSpan.recordException(e);
      s.guardSpan.setStatus(StatusCode.ERROR, "response inspection failed");
      out.onNext(Responses.immediateHttpError(500, "security inspection failure"));
    }
  }

  static final class SessionState {
    Context guardContext;
    Span guardSpan;
    String requestId;
    String mcpMethod;
    String toolName;
  }
}
```

### 4) Pangea wrapper

```java
public record GuardDecision(String recipe, boolean blocked, String code) {}

public interface PangeaGuardClient {
  GuardDecision guard(String recipe, String text, String toolName);
}
```

If you prefer raw REST rather than the SDK, call Pangea AI Guard `/v1/text/guard` with fields such as `recipe`, `text`, and `log_fields.tools`. If you prefer the SDK, CrowdStrike documents a Java client with `AIGuardClient.guardText(...)`. ([Pangea][11])

---

## Error-handling recommendation

For a blocked **valid MCP JSON-RPC** call, I recommend returning a **JSON-RPC error body** rather than only an HTTP 403, because the client is already speaking MCP/JSON-RPC. Use HTTP-only errors for transport failures, malformed requests, or cases where you never had a valid MCP message to answer. That keeps clients more interoperable with the protocol while still surfacing a clear policy code in the error data and in `mcp.edge.guard`. This is a design recommendation based on the MCP transport shape. ([Model Context Protocol][4])

## Operational guardrails

A few details matter in production:

* Use **mTLS** between Flex and `mcp-edge-guard`.
* Keep `failureModeAllow=false` unless you have a deliberate break-glass posture. ([MuleSoft Documentation][1])
* Put `mcp-edge-guard` behind an internal AKS service with HPA.
* Do not send raw huge payloads to Pangea; canonicalize and size-bound them because AI Guard’s documented limit is 20 KiB. ([Pangea][11])
* If you have SSE responses, inspect **per complete event** or use a bounded buffering strategy. MCP transport explicitly allows `text/event-stream`. ([Model Context Protocol][4])
* Expect to see both **Envoy/Flex ext-proc spans** and your own semantic spans. That is fine; keep the semantic value on `mcp.edge.guard` and the Pangea child spans. ([Envoy Proxy][12])

## Bottom line

The design I recommend is:

**API-manager-driven MCP policy bundle + one shared ext-proc helper service in AKS.**

That is the smallest reusable pattern that meets all of your requirements:

* no per-team MCP server code
* full request/response security interception
* dedicated child spans per Pangea evaluation
* a single semantic wrapper span for the interaction at the edge
* compatibility with MuleSoft MCP mediation and Dynatrace OneAgent

The only thing I would not promise with current Flex capabilities is **putting body-derived and per-baggage-key attributes directly on the native Flex gateway span itself**. For that, the supported and maintainable answer is to put them on the helper’s `mcp.edge.guard` span instead. That still gives you a centralized, standardized, and supportable solution. ([MuleSoft Documentation][5])


