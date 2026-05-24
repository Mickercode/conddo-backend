package io.conddo.api.web.dto;

import io.conddo.core.domain.Booking;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Confirmation of a public self-booking (§11.5). Status is {@code pending}. */
public record PublicBookingResponse(UUID id, String status, OffsetDateTime start, OffsetDateTime end) {

    public static PublicBookingResponse from(Booking b) {
        return new PublicBookingResponse(b.getId(), b.getStatus(), b.getStartsAt(), b.getEndsAt());
    }
}
