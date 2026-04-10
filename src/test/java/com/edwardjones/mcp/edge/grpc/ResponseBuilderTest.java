package com.edwardjones.mcp.edge.grpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.envoyproxy.envoy.service.ext_proc.v3.*;
import io.envoyproxy.envoy.type.v3.StatusCode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResponseBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void continueHeadersWithMutations() {
        Map<String, String> headers = Map.of(
                "traceparent", "00-abc-def-01",
                "tracestate", "vendor=value"
        );

        ProcessingResponse resp = ResponseBuilder.continueHeaders(headers);

        assertTrue(resp.hasRequestHeaders());
        assertEquals(CommonResponse.ResponseStatus.CONTINUE,
                resp.getRequestHeaders().getResponse().getStatus());

        var setHeaders = resp.getRequestHeaders().getResponse()
                .getHeaderMutation().getSetHeadersList();
        assertEquals(2, setHeaders.size());
    }

    @Test
    void continueHeadersWithEmptyMap() {
        ProcessingResponse resp = ResponseBuilder.continueHeaders(Map.of());

        assertTrue(resp.hasRequestHeaders());
        assertEquals(CommonResponse.ResponseStatus.CONTINUE,
                resp.getRequestHeaders().getResponse().getStatus());
        assertEquals(0, resp.getRequestHeaders().getResponse()
                .getHeaderMutation().getSetHeadersCount());
    }

    @Test
    void continueRequestBody() {
        ProcessingResponse resp = ResponseBuilder.continueRequestBody();

        assertTrue(resp.hasRequestBody());
        assertEquals(CommonResponse.ResponseStatus.CONTINUE,
                resp.getRequestBody().getResponse().getStatus());
    }

    @Test
    void continueAndReplaceBody() {
        ProcessingResponse resp = ResponseBuilder.continueAndReplaceBody("replacement".getBytes());

        assertTrue(resp.hasRequestBody());
        assertEquals(CommonResponse.ResponseStatus.CONTINUE_AND_REPLACE,
                resp.getRequestBody().getResponse().getStatus());
        assertEquals("replacement", resp.getRequestBody().getResponse()
                .getBodyMutation().getBody().toStringUtf8());
    }

    @Test
    void continueResponseHeaders() {
        ProcessingResponse resp = ResponseBuilder.continueResponseHeaders();

        assertTrue(resp.hasResponseHeaders());
        assertEquals(CommonResponse.ResponseStatus.CONTINUE,
                resp.getResponseHeaders().getResponse().getStatus());
    }

    @Test
    void continueResponseBody() {
        ProcessingResponse resp = ResponseBuilder.continueResponseBody();

        assertTrue(resp.hasResponseBody());
        assertEquals(CommonResponse.ResponseStatus.CONTINUE,
                resp.getResponseBody().getResponse().getStatus());
    }

    @Test
    void immediateJsonRpcError() {
        ProcessingResponse resp = ResponseBuilder.immediateJsonRpcError(
                "42", -32001, "Blocked by security policy", "PII_DETECTED");

        assertTrue(resp.hasImmediateResponse());
        ImmediateResponse ir = resp.getImmediateResponse();
        assertEquals(StatusCode.Forbidden, ir.getStatus().getCode());
        assertTrue(ir.getBody().toStringUtf8().contains("\"id\":42"));
        assertTrue(ir.getBody().toStringUtf8().contains("-32001"));
        assertTrue(ir.getBody().toStringUtf8().contains("Blocked by security policy"));
        assertTrue(ir.getBody().toStringUtf8().contains("PII_DETECTED"));

        var contentType = ir.getHeaders().getSetHeadersList().stream()
                .filter(h -> h.getHeader().getKey().equals("content-type"))
                .findFirst();
        assertTrue(contentType.isPresent());
        assertEquals("application/json", contentType.get().getHeader().getValue());
    }

    @Test
    void immediateJsonRpcErrorPreservesStringIdAndEscapesFields() throws Exception {
        ProcessingResponse resp = ResponseBuilder.immediateJsonRpcError(
                "\"abc-123\"", -32001, "Blocked \"quoted\" value", "CODE\"X");

        JsonNode body = MAPPER.readTree(resp.getImmediateResponse().getBody().toStringUtf8());

        assertEquals("abc-123", body.path("id").asText());
        assertEquals("Blocked \"quoted\" value", body.path("error").path("message").asText());
        assertEquals("CODE\"X", body.path("error").path("data").path("security_code").asText());
    }

    @Test
    void immediateJsonRpcErrorWithNullCode() {
        ProcessingResponse resp = ResponseBuilder.immediateJsonRpcError(
                "1", -32001, "Blocked", null);

        assertTrue(resp.getImmediateResponse().getBody().toStringUtf8().contains("unknown"));
    }

    @Test
    void replaceResponseWithJsonRpcError() {
        ProcessingResponse resp = ResponseBuilder.replaceResponseWithJsonRpcError(
                "5", -32002, "Response blocked", "MALWARE");

        assertTrue(resp.hasResponseBody());
        assertEquals(CommonResponse.ResponseStatus.CONTINUE_AND_REPLACE,
                resp.getResponseBody().getResponse().getStatus());

        String body = resp.getResponseBody().getResponse()
                .getBodyMutation().getBody().toStringUtf8();
        assertTrue(body.contains("\"id\":5"));
        assertTrue(body.contains("-32002"));
        assertTrue(body.contains("Response blocked"));
        assertTrue(body.contains("MALWARE"));
    }

    @Test
    void replaceResponseWithJsonRpcErrorUsesNullId() throws Exception {
        ProcessingResponse resp = ResponseBuilder.replaceResponseWithJsonRpcError(
                "null", -32002, "Response blocked", "MALWARE");

        String body = resp.getResponseBody().getResponse()
                .getBodyMutation().getBody().toStringUtf8();

        assertTrue(MAPPER.readTree(body).path("id").isNull());
    }

    @Test
    void immediateHttpError() {
        ProcessingResponse resp = ResponseBuilder.immediateHttpError(500, "internal error");

        assertTrue(resp.hasImmediateResponse());
        assertEquals(StatusCode.InternalServerError,
                resp.getImmediateResponse().getStatus().getCode());
        assertEquals("internal error", resp.getImmediateResponse().getBody().toStringUtf8());

        var contentType = resp.getImmediateResponse().getHeaders().getSetHeadersList().stream()
                .filter(h -> h.getHeader().getKey().equals("content-type"))
                .findFirst();
        assertTrue(contentType.isPresent());
        assertEquals("text/plain", contentType.get().getHeader().getValue());
    }

    @Test
    void immediateHttpErrorMapsStatusCodes() {
        assertEquals(StatusCode.BadRequest,
                ResponseBuilder.immediateHttpError(400, "bad").getImmediateResponse().getStatus().getCode());
        assertEquals(StatusCode.Unauthorized,
                ResponseBuilder.immediateHttpError(401, "auth").getImmediateResponse().getStatus().getCode());
        assertEquals(StatusCode.Forbidden,
                ResponseBuilder.immediateHttpError(403, "no").getImmediateResponse().getStatus().getCode());
        assertEquals(StatusCode.NotFound,
                ResponseBuilder.immediateHttpError(404, "missing").getImmediateResponse().getStatus().getCode());
        assertEquals(StatusCode.TooManyRequests,
                ResponseBuilder.immediateHttpError(429, "slow").getImmediateResponse().getStatus().getCode());
        assertEquals(StatusCode.BadGateway,
                ResponseBuilder.immediateHttpError(502, "bad gateway").getImmediateResponse().getStatus().getCode());
        assertEquals(StatusCode.ServiceUnavailable,
                ResponseBuilder.immediateHttpError(503, "down").getImmediateResponse().getStatus().getCode());
        // Unknown codes map to 500
        assertEquals(StatusCode.InternalServerError,
                ResponseBuilder.immediateHttpError(418, "teapot").getImmediateResponse().getStatus().getCode());
    }
}
