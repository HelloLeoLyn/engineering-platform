package com.engineeringplatform.web.response;

import java.time.Instant;

public record ApiResponse<T>(boolean success, String code, String message, T data, String traceId, Instant timestamp) {
    public static <T> ApiResponse<T> success(T data, String traceId) {
        return new ApiResponse<>(true, "SUCCESS", null, data, traceId, Instant.now());
    }
    public static ApiResponse<Void> success(String traceId) { return success(null, traceId); }
    public static <T> ApiResponse<T> failure(String code, String message, T data, String traceId) {
        return new ApiResponse<>(false, code, message, data, traceId, Instant.now());
    }
}
