package io.conddo.payments.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * Body for {@code POST /api/payments/charges}. Exactly one of {@code orderId}
 * or {@code bookingId} is required (validated server-side); {@code amount} is
 * naira × 100 (kobo) as a positive integer.
 */
public record InitPaymentRequest(UUID orderId,
                                 UUID bookingId,
                                 UUID customerId,
                                 @NotBlank @Email String customerEmail,
                                 @NotBlank String customerName,
                                 String description,
                                 String returnUrl,
                                 @Positive long amountKobo) {
}
