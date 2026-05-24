package io.conddo.api.web.dto;

/** Send a payment/pickup reminder for an order (§11.4). {@code message} optional. */
public record ReminderRequest(String message) {
}
