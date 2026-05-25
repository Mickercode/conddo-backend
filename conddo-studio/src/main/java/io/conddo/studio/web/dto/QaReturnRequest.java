package io.conddo.studio.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/** Return a submission for revision, with feedback. */
public record QaReturnRequest(Map<String, Object> checklist, @NotBlank String feedback) {
}
