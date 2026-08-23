package com.festix.festix.common.response;

public record ApiResponse<T>(
        boolean success,
        T data,
        ErrorPayload error
) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> empty() {
        return new ApiResponse<>(true, null, null);
    }

    public static ApiResponse<Void> error(String code, String message) {
        return new ApiResponse<>(false, null, new ErrorPayload(code, message));
    }

    public record ErrorPayload(String code, String message) {
    }
}
