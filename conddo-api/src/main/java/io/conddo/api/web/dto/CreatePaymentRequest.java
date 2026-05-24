package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** Record a payment against an order (§11.4). */
public record CreatePaymentRequest(
        @NotNull @Positive BigDecimal amount,
        String method,
        String note
) {
}
