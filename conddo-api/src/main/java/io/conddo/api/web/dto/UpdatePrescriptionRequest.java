package io.conddo.api.web.dto;

/**
 * PATCH body. Field-absent = "leave alone"; explicit value = "set to that
 * value". Per the spec, callers can clear {@code refillIntervalDays} (turning
 * a recurring script into a one-off) by sending {@code clearRefillInterval:
 * true} — that's our explicit-clear sentinel because Jackson records can't
 * distinguish "key absent" from "explicit null" without custom deserializers.
 */
public record UpdatePrescriptionRequest(
        String medication,
        String dosage,
        Integer quantity,
        Integer refillIntervalDays,
        Boolean clearRefillInterval,
        String notes) {

    /** True when the caller wants the service to touch refillIntervalDays at all. */
    public boolean refillIntervalKeyPresent() {
        return refillIntervalDays != null || Boolean.TRUE.equals(clearRefillInterval);
    }
}
