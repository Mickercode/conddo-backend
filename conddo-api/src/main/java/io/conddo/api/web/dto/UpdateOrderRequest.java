package io.conddo.api.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * PATCH an order (§11.4). All optional — only sent fields change. Stage changes
 * go through {@code POST /orders/{id}/transition} (which logs the move), not here.
 * {@code amount} is honoured only when the order has no line items.
 */
public record UpdateOrderRequest(
        String service,
        LocalDate dueDate,
        String flag,
        BigDecimal amount,
        String notes
) {
}
