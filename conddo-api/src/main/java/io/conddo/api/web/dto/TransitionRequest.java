package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Move an order to a new pipeline stage (§11.4). */
public record TransitionRequest(@NotBlank String stage) {
}
