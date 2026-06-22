package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request DTO for creating/updating fashion products.
 */
public record FashionProductRequest(
        @NotBlank String name,
        String sku,
        @NotBlank String category,
        @NotBlank String material,
        @NotNull @Positive BigDecimal basePrice,
        List<VariantDto> variants,
        Boolean active
) {}
