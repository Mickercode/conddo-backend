package io.conddo.api.web.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * PATCH a booking (§11.5): reschedule ({@code start}/{@code end}), change the
 * service/mode, set status (e.g. {@code cancelled}/{@code completed}), or edit
 * amount/notes. All optional — only sent fields change.
 */
public record UpdateBookingRequest(
        OffsetDateTime start,
        OffsetDateTime end,
        String service,
        String mode,
        String status,
        BigDecimal amount,
        String notes
) {
}
