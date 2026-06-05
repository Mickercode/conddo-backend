package io.conddo.api.web.dto;

/** Optional custom message. Omit / null = use the default reminder template. */
public record PrescriptionRemindRequest(String message) {
}
