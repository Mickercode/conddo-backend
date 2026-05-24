package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Connect a social account (§11.8): platform + handle (real OAuth deferred). */
public record ConnectRequest(@NotBlank String platform, String handle) {
}
