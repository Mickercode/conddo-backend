package io.conddo.api.web.dto;

import io.conddo.core.service.FashionOrderService;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Fashion order DTO with shoe product selection.
 */
public record FashionOrderDto(
        UUID id,
        String reference,
        UUID customerId,
        String customerName,
        String stage,
        BigDecimal totalAmount,
        OffsetDateTime orderDate,
        OffsetDateTime expectedDelivery,
        String notes,
        String flag,
        List<FashionOrderItemDto> items
) {
    public static FashionOrderDto from(FashionOrderService.FashionOrderView view) {
        return new FashionOrderDto(
                view.id(),
                view.reference(),
                view.customerId(),
                view.customerName(),
                view.stage(),
                view.totalAmount(),
                view.orderDate(),
                view.expectedDelivery(),
                view.notes(),
                view.flag(),
                view.items().stream().map(i -> new FashionOrderItemDto(
                        i.shoeId(), i.shoeName(), i.size(), i.color(),
                        i.quantity(), i.unitPrice(), i.totalPrice())).toList()
        );
    }
}
