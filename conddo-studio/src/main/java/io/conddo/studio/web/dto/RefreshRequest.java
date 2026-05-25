package io.conddo.studio.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Exchange a refresh token for a new access token. */
public record RefreshRequest(@NotBlank String refreshToken) {
}
