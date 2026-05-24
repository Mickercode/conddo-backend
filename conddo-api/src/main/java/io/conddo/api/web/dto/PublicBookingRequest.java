package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/** A client's self-book request (§11.5 PUBLIC). Lands as a pending booking. */
public record PublicBookingRequest(
        @NotBlank String customerName,
        String customerPhone,
        String service,
        @NotNull OffsetDateTime start
) {
}
