package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * DTO for size/color variant with stock.
 */
public record VariantDto(
        @NotBlank String size,
        @NotBlank String color,
        @PositiveOrZero int stock
) {}
