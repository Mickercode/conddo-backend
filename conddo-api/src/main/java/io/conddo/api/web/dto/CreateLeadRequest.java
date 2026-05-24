package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Create a marketing lead (§11.8). */
public record CreateLeadRequest(@NotBlank String name, String email, String phone, String source) {
}
