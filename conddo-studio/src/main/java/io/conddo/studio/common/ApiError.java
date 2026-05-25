package io.conddo.studio.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Error body inside {@link ApiResponse}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
