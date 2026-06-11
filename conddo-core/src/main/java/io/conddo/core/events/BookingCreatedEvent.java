package io.conddo.core.events;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Fired when a booking is created. Mirrors {@link OrderCreatedEvent}
 * — only the public-website source fans out an owner notification
 * (the dashboard-typed bookings are already in front of the staff
 * member typing them in). Consumers reload the {@code Booking} by
 * id so they always see the committed state.
 */
public record BookingCreatedEvent(
        UUID tenantId,
        UUID bookingId,
        String customerName,
        String service,
        OffsetDateTime startsAt,
        String contactPhone,
        Source source) {

    public enum Source {
        /** Dashboard-typed booking. */
        DASHBOARD,
        /** Booking placed via the merchant's public booking link. */
        PUBLIC_WEBSITE
    }
}
