package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.FashionOrder;
import io.conddo.core.repository.FashionOrderRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Fashion-specific order service with shoe product selection.
 * Tenant-scoped via RLS.
 */
@Service
public class FashionOrderService {

    private final FashionOrderRepository fashionOrderRepository;
    private final TenantSession tenantSession;

    public FashionOrderService(FashionOrderRepository fashionOrderRepository,
                                TenantSession tenantSession) {
        this.fashionOrderRepository = fashionOrderRepository;
        this.tenantSession = tenantSession;
    }

    @Transactional(readOnly = true)
    public Page<FashionOrderView> list(String search, String stage, Pageable pageable) {
        tenantSession.bind();
        // For now, return all orders with pagination
        // TODO: Implement proper filtering with Specifications
        Page<FashionOrder> page = fashionOrderRepository.findAll(pageable);
        return page.map(FashionOrderView::from);
    }

    @Transactional
    public FashionOrderView create(String reference, UUID customerId, String customerName,
                                    String stage, List<FashionOrder.FashionOrderItem> items) {
        tenantSession.bind();
        UUID tenantId = TenantContext.require();
        BigDecimal totalAmount = items != null ? items.stream()
            .map(FashionOrder.FashionOrderItem::getTotalPrice)
            .reduce(BigDecimal.ZERO, BigDecimal::add) : BigDecimal.ZERO;
        
        FashionOrder order = new FashionOrder(tenantId, reference, customerId, customerName,
                stage, totalAmount, OffsetDateTime.now(), items);
        return FashionOrderView.from(fashionOrderRepository.save(order));
    }

    @Transactional(readOnly = true)
    public FashionOrderView get(UUID id) {
        tenantSession.bind();
        FashionOrder order = fashionOrderRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Fashion order not found"));
        return FashionOrderView.from(order);
    }

    @Transactional
    public FashionOrderView update(UUID id, String stage, OffsetDateTime expectedDelivery,
                                    String notes, String flag) {
        tenantSession.bind();
        FashionOrder order = fashionOrderRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Fashion order not found"));
        
        if (stage != null && !stage.isBlank()) {
            order.setStage(stage);
        }
        if (expectedDelivery != null) {
            order.setExpectedDelivery(expectedDelivery);
        }
        if (notes != null) {
            order.setNotes(notes);
        }
        if (flag != null) {
            order.setFlag(flag);
        }
        
        return FashionOrderView.from(fashionOrderRepository.save(order));
    }

    @Transactional
    public void delete(UUID id) {
        tenantSession.bind();
        FashionOrder order = fashionOrderRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Fashion order not found"));
        fashionOrderRepository.delete(order);
    }

    /** View DTO for fashion orders. */
    public record FashionOrderView(
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
        List<OrderItemView> items
    ) {
        static FashionOrderView from(FashionOrder o) {
            return new FashionOrderView(
                o.getId(),
                o.getReference(),
                o.getCustomerId(),
                o.getCustomerName(),
                o.getStage(),
                o.getTotalAmount(),
                o.getOrderDate(),
                o.getExpectedDelivery(),
                o.getNotes(),
                o.getFlag(),
                o.getItems() != null ? o.getItems().stream()
                    .map(i -> new OrderItemView(i.getShoeId(), i.getShoeName(), i.getSize(),
                        i.getColor(), i.getQuantity(), i.getUnitPrice(), i.getTotalPrice()))
                    .toList() : List.of()
            );
        }
    }

    public record OrderItemView(
        UUID shoeId,
        String shoeName,
        String size,
        String color,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal totalPrice
    ) {}
}
