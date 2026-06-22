package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Request DTO for creating/updating fashion orders.
 */
public record FashionOrderRequest(
        @NotBlank String reference,
        UUID customerId,
        @NotBlank String customerName,
        String stage,
        OffsetDateTime expectedDelivery,
        String notes,
        String flag,
        List<FashionOrderItemDto> items
) {}
