package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * DTO for fashion order item with size/color selection.
 */
public record FashionOrderItemDto(
        @NotNull UUID shoeId,
        @NotBlank String shoeName,
        @NotBlank String size,
        @NotBlank String color,
        @Positive int quantity,
        @NotNull @Positive BigDecimal unitPrice,
        BigDecimal totalPrice
) {}
