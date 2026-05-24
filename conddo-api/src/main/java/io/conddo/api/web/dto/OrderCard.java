package io.conddo.api.web.dto;

import io.conddo.core.common.Initials;
import io.conddo.core.domain.Order;
import io.conddo.core.service.OrderService.OrderView;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A board card / list row for an order (§11.4). {@code id} is the canonical
 * UUID; {@code reference} is the "ORD-2894" display label. {@code flag} is the
 * derived OVERDUE/URGENT marker.
 */
public record OrderCard(
        UUID id,
        String reference,
        String customer,
        String service,
        BigDecimal amount,
        OffsetDateTime date,
        String initials,
        String stage,
        String flag
) {
    public static OrderCard from(OrderView v) {
        Order o = v.order();
        return new OrderCard(o.getId(), o.getReference(), o.getCustomerName(), o.getService(),
                o.getAmount(), o.getCreatedAt(), Initials.of(o.getCustomerName()), o.getStage(), v.flag());
    }
}
