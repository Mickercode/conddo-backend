package io.conddo.studio.web.dto;

/** Manually escalate a job, with an optional reason. */
public record EscalateRequest(String reason) {
}
