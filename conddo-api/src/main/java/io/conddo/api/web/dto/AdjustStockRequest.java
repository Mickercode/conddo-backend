package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotNull;

/** Adjust a product's stock by a signed delta (§11.6), e.g. {@code {"delta": -3, "reason": "Sold"}}. */
public record AdjustStockRequest(@NotNull Integer delta, String reason) {
}
