package io.conddo.payments.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * Body for the service-to-service {@code POST /api/payments/internal/charges}
 * — conddo-api uses this when a tenant flow needs a payment without having
 * a tenant Bearer JWT to forward (the FE → conddo-api hop is tenant-authed;
 * the conddo-api → payments hop uses the service token).
 *
 * <p>Exactly one of {@code orderId} / {@code bookingId} /
 * {@code creativeRequestId} / {@code brandPackageSubscriptionId} must be set
 * — the set field drives the routing decision (sub-account fan-out for
 * booking + order; platform-account direct for the Conddo-paid kinds).
 *
 * <p>{@code customerEmail} / {@code customerName} accept the merchant
 * owner's details on platform charges (creative service / brand package) —
 * RoutePay still needs a payer identity even when the money lands on our
 * master account, so the field names stay "customer*" for compat.
 */
public record InitChargeServiceRequest(@NotNull UUID tenantId,
                                       @NotBlank String tenantSlug,
                                       UUID orderId,
                                       UUID bookingId,
                                       UUID creativeRequestId,
                                       UUID brandPackageSubscriptionId,
                                       UUID customerId,
                                       @NotBlank @Email String customerEmail,
                                       @NotBlank String customerName,
                                       String description,
                                       String returnUrl,
                                       @Positive long amountKobo) {
}
