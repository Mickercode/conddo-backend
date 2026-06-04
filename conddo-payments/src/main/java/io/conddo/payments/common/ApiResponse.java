package io.conddo.payments.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standard API envelope — same shape as conddo-api / conddo-studio
 * ({@code {success, data, meta?, error?}}) so the tenant FE reads it
 * identically. Duplicated here to keep the service standalone.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, Meta meta, ApiError error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    public static <T> ApiResponse<T> ok(T data, Meta meta) {
        return new ApiResponse<>(true, data, meta, null);
    }

    public static <T> ApiResponse<T> fail(ApiError error) {
        return new ApiResponse<>(false, null, null, error);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Meta(Integer page, Integer size, Long total) {
        public static Meta page(int page, int size, long total) {
            return new Meta(page, size, total);
        }
    }
}
