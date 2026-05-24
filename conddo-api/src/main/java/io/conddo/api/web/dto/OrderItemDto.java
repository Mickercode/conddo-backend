package io.conddo.api.web.dto;

import io.conddo.core.domain.OrderItem;

import java.math.BigDecimal;
import java.util.UUID;

/** An order line item (§11.4). {@code total} = quantity × unitPrice. */
public record OrderItemDto(
        UUID id,
        String description,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal total
) {
    public static OrderItemDto from(OrderItem i) {
        return new OrderItemDto(i.getId(), i.getDescription(), i.getQuantity(), i.getUnitPrice(), i.getTotal());
    }
}
