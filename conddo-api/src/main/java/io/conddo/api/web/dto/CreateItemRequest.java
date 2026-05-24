package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

/** Add a line item to an order (§11.4). */
public record CreateItemRequest(
        @NotBlank String description,
        Integer quantity,
        BigDecimal unitPrice
) {
}
