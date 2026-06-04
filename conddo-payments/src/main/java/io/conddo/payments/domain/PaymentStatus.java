package io.conddo.payments.domain;

/**
 * One-way payment state machine. Terminal statuses ({@code PAID},
 * {@code FAILED}, {@code REFUNDED}, {@code EXPIRED}) are never reverted, even
 * if RoutePay re-posts a webhook for the same payment.
 */
public enum PaymentStatus {
    PENDING,
    PAID,
    FAILED,
    REFUNDED,
    EXPIRED;

    public boolean isTerminal() {
        return this != PENDING;
    }

    public static PaymentStatus parse(String raw) {
        return raw == null ? PENDING : PaymentStatus.valueOf(raw.trim().toUpperCase());
    }
}
