package io.conddo.studio.web.dto;

/** Submit a job for QA. */
public record SubmitJobRequest(String studioUrl, String notes) {
}
