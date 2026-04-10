package com.edwardjones.mcp.edge.grpc;

import com.google.protobuf.ByteString;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.envoyproxy.envoy.service.ext_proc.v3.*;
import io.envoyproxy.envoy.type.v3.HttpStatus;
import io.envoyproxy.envoy.type.v3.StatusCode;

import java.util.Map;

public final class ResponseBuilder {

    public static ProcessingResponse continueHeaders(Map<String, String> additionalHeaders) {
        HeaderMutation.Builder mutation = HeaderMutation.newBuilder();
        additionalHeaders.forEach((key, value) ->
                mutation.addSetHeaders(HeaderValueOption.newBuilder()
                        .setHeader(HeaderValue.newBuilder()
                                .setKey(key)
                                .setValue(value)
                                .build())
                        .build()));

        return ProcessingResponse.newBuilder()
                .setRequestHeaders(HeadersResponse.newBuilder()
                        .setResponse(CommonResponse.newBuilder()
                                .setStatus(CommonResponse.ResponseStatus.CONTINUE)
                                .setHeaderMutation(mutation.build())
                                .build())
                        .build())
                .build();
    }

    public static ProcessingResponse continueAndReplaceBody(byte[] newBody) {
        return ProcessingResponse.newBuilder()
                .setRequestBody(BodyResponse.newBuilder()
                        .setResponse(CommonResponse.newBuilder()
                                .setStatus(CommonResponse.ResponseStatus.CONTINUE)
                                .build())
                        .build())
                .build();
    }

    public static ProcessingResponse continueRequestBody() {
        return ProcessingResponse.newBuilder()
                .setRequestBody(BodyResponse.newBuilder()
                        .setResponse(CommonResponse.newBuilder()
                                .setStatus(CommonResponse.ResponseStatus.CONTINUE)
                                .build())
                        .build())
                .build();
    }

    public static ProcessingResponse continueResponseHeaders() {
        return ProcessingResponse.newBuilder()
                .setResponseHeaders(HeadersResponse.newBuilder()
                        .setResponse(CommonResponse.newBuilder()
                                .setStatus(CommonResponse.ResponseStatus.CONTINUE)
                                .build())
                        .build())
                .build();
    }

    public static ProcessingResponse continueResponseBody() {
        return ProcessingResponse.newBuilder()
                .setResponseBody(BodyResponse.newBuilder()
                        .setResponse(CommonResponse.newBuilder()
                                .setStatus(CommonResponse.ResponseStatus.CONTINUE)
                                .build())
                        .build())
                .build();
    }

    public static ProcessingResponse immediateJsonRpcError(String requestId, int code, String message, String securityCode) {
        String jsonBody = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":%s,\"error\":{\"code\":%d,\"message\":\"%s\",\"data\":{\"security_code\":\"%s\"}}}",
                requestId, code, message, securityCode != null ? securityCode : "unknown");

        return ProcessingResponse.newBuilder()
                .setImmediateResponse(ImmediateResponse.newBuilder()
                        .setStatus(HttpStatus.newBuilder()
                                .setCode(StatusCode.Forbidden)
                                .build())
                        .setHeaders(HeaderMutation.newBuilder()
                                .addSetHeaders(HeaderValueOption.newBuilder()
                                        .setHeader(HeaderValue.newBuilder()
                                                .setKey("content-type")
                                                .setValue("application/json")
                                                .build())
                                        .build())
                                .build())
                        .setBody(ByteString.copyFromUtf8(jsonBody))
                        .build())
                .build();
    }

    public static ProcessingResponse replaceResponseWithJsonRpcError(String requestId, int code, String message, String securityCode) {
        String jsonBody = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":%s,\"error\":{\"code\":%d,\"message\":\"%s\",\"data\":{\"security_code\":\"%s\"}}}",
                requestId, code, message, securityCode != null ? securityCode : "unknown");

        return ProcessingResponse.newBuilder()
                .setResponseBody(BodyResponse.newBuilder()
                        .setResponse(CommonResponse.newBuilder()
                                .setStatus(CommonResponse.ResponseStatus.CONTINUE_AND_REPLACE)
                                .setBodyMutation(BodyMutation.newBuilder()
                                        .setBody(ByteString.copyFromUtf8(jsonBody))
                                        .build())
                                .setHeaderMutation(HeaderMutation.newBuilder()
                                        .addSetHeaders(HeaderValueOption.newBuilder()
                                                .setHeader(HeaderValue.newBuilder()
                                                        .setKey("content-type")
                                                        .setValue("application/json")
                                                        .build())
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();
    }

    public static ProcessingResponse immediateHttpError(int statusCode, String message) {
        StatusCode code = mapHttpStatusCode(statusCode);

        return ProcessingResponse.newBuilder()
                .setImmediateResponse(ImmediateResponse.newBuilder()
                        .setStatus(HttpStatus.newBuilder()
                                .setCode(code)
                                .build())
                        .setHeaders(HeaderMutation.newBuilder()
                                .addSetHeaders(HeaderValueOption.newBuilder()
                                        .setHeader(HeaderValue.newBuilder()
                                                .setKey("content-type")
                                                .setValue("text/plain")
                                                .build())
                                        .build())
                                .build())
                        .setBody(ByteString.copyFromUtf8(message))
                        .build())
                .build();
    }

    private static StatusCode mapHttpStatusCode(int httpCode) {
        return switch (httpCode) {
            case 400 -> StatusCode.BadRequest;
            case 401 -> StatusCode.Unauthorized;
            case 403 -> StatusCode.Forbidden;
            case 404 -> StatusCode.NotFound;
            case 429 -> StatusCode.TooManyRequests;
            case 500 -> StatusCode.InternalServerError;
            case 502 -> StatusCode.BadGateway;
            case 503 -> StatusCode.ServiceUnavailable;
            default -> StatusCode.InternalServerError;
        };
    }

    private ResponseBuilder() {
    }
}
