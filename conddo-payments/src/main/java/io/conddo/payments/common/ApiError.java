package io.conddo.payments.common;

import java.util.List;

/** Error body inside {@link ApiResponse}. {@code details} carries per-field issues for 400s. */
public record ApiError(String code, String message, List<FieldError> details) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null);
    }

    public static ApiError of(String code, String message, List<FieldError> details) {
        return new ApiError(code, message, details);
    }

    public record FieldError(String field, String message) {
    }
}
