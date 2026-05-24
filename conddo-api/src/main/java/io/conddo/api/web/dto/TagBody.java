package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;

/** POST body to add a tag to a customer (§11.3), e.g. {@code {"tag":"VIP"}}. */
public record TagBody(@NotBlank String tag) {
}
