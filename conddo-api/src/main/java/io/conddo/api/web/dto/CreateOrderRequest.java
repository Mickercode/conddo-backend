package io.conddo.api.web.dto;

import io.conddo.core.service.OrderService.NewItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Create an order (§11.4). Link a CRM customer via {@code customerId} (its name
 * is snapshotted) or pass a free-text {@code customerName}. {@code stage}
 * defaults to the pipeline's first stage; {@code amount} is derived from
 * {@code items} when any are given.
 */
public record CreateOrderRequest(
        UUID customerId,
        String customerName,
        String service,
        String stage,
        BigDecimal amount,
        LocalDate dueDate,
        List<ItemInput> items,
        Map<String, Object> measurements,
        String notes
) {
    public record ItemInput(String description, Integer quantity, BigDecimal unitPrice) {
    }

    /** Maps the request's items to the service's input records. */
    public List<NewItem> toNewItems() {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .map(i -> new NewItem(i.description(),
                        i.quantity() == null ? 1 : i.quantity(),
                        i.unitPrice() == null ? BigDecimal.ZERO : i.unitPrice()))
                .toList();
    }
}
