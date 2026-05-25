package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Body for {@code POST /api/v1/payments/reminders} (§11.7) — send an
 * outstanding-balance reminder to a customer. {@code message} is optional (a
 * default is composed from the owed amount). Invoice-targeted reminders await
 * the Billing module.
 */
public record PaymentReminderRequest(@NotNull UUID customerId, String message) {
}
