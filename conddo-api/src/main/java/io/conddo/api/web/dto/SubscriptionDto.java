package io.conddo.api.web.dto;

import io.conddo.core.service.BillingService;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Subscription wire shape (BILLING_TIERS_SPEC §4). {@code amountPaid} is in
 * <b>Naira</b>; {@code daysRemaining} is derived server-side so the FE doesn't
 * have to compare clocks.
 */
public record SubscriptionDto(
        String planId,
        String planDisplayName,
        String billingCycle,
        String status,
        Integer amountPaid,              // Naira
        OffsetDateTime startedAt,
        OffsetDateTime expiresAt,
        OffsetDateTime trialEndsAt,
        OffsetDateTime cancelledAt,
        long daysRemaining) {

    public static SubscriptionDto from(BillingService.SubscriptionWithPlan s) {
        long days = s.subscription() == null ? 0
                : Math.max(0L, Duration.between(
                        OffsetDateTime.now(), s.subscription().getExpiresAt()).toDays());
        return new SubscriptionDto(
                s.plan() == null ? null : s.plan().getName(),
                s.plan() == null ? null : s.plan().getDisplayName(),
                s.subscription().getBillingCycle(),
                s.subscription().getStatus(),
                s.subscription().getAmountPaid() / 100,
                s.subscription().getStartedAt(),
                s.subscription().getExpiresAt(),
                s.subscription().getTrialEndsAt(),
                s.subscription().getCancelledAt(),
                days);
    }
}
