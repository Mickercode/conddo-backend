package io.conddo.api.web.dto;

import io.conddo.core.domain.Booking;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A calendar event / booking (§11.5). The list view uses the core fields
 * {@code {id, customer, service, start, end, mode, status}}; detail also reads
 * {@code amount} and {@code notes}.
 */
public record BookingEvent(
        UUID id,
        UUID customerId,
        String customer,
        String service,
        OffsetDateTime start,
        OffsetDateTime end,
        String mode,
        String status,
        BigDecimal amount,
        String notes
) {
    public static BookingEvent from(Booking b) {
        return new BookingEvent(b.getId(), b.getCustomerId(), b.getCustomerName(), b.getService(),
                b.getStartsAt(), b.getEndsAt(), b.getMode(), b.getStatus(), b.getAmount(), b.getNotes());
    }
}
