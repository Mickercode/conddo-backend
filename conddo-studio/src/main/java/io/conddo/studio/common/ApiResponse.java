package io.conddo.studio.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standard API envelope — same shape as the platform's ({@code {success, data,
 * meta?, error?}}) so the Jobs Board frontend reads it identically. Duplicated
 * here to keep this service standalone.
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
        public static Meta total(long total) {
            return new Meta(null, null, total);
        }

        public static Meta page(int page, int size, long total) {
            return new Meta(page, size, total);
        }
    }
}
