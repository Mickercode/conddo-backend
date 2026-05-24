package io.conddo.api.web.dto;

import io.conddo.core.domain.OrderPayment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A payment recorded against an order (§11.4). */
public record OrderPaymentDto(
        UUID id,
        BigDecimal amount,
        String method,
        String note,
        OffsetDateTime paidAt
) {
    public static OrderPaymentDto from(OrderPayment p) {
        return new OrderPaymentDto(p.getId(), p.getAmount(), p.getMethod(), p.getNote(), p.getPaidAt());
    }
}
