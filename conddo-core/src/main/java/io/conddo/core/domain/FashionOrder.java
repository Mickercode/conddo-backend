package io.conddo.core.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Fashion-specific order with shoe product selection (Fashion vertical).
 * Tenant-scoped via RLS. Extends the generic Order concept with fashion-specific
 * attributes like size/color selection per item.
 */
@Entity
@Table(name = "fashion_orders")
public class FashionOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "reference", nullable = false)
    private String reference;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(nullable = false)
    private String stage; // Received, Processing, Production, Quality Check, Ready, Shipped, Delivered

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "order_date", nullable = false)
    private OffsetDateTime orderDate;

    @Column(name = "expected_delivery")
    private OffsetDateTime expectedDelivery;

    @Column
    private String notes;

    @Column
    private String flag; // URGENT, OVERDUE

    /** Fashion order items with size/color selection. */
    @JdbcTypeCode(SqlTypes.JSON)
    private List<FashionOrderItem> items;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected FashionOrder() {
    }

    public FashionOrder(UUID tenantId, String reference, UUID customerId, String customerName,
                        String stage, BigDecimal totalAmount, OffsetDateTime orderDate,
                        List<FashionOrderItem> items) {
        this.tenantId = tenantId;
        this.reference = reference;
        this.customerId = customerId;
        this.customerName = customerName;
        this.stage = stage;
        this.totalAmount = totalAmount == null ? BigDecimal.ZERO : totalAmount;
        this.orderDate = orderDate;
        this.items = items;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getReference() {
        return reference;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getStage() {
        return stage;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public OffsetDateTime getOrderDate() {
        return orderDate;
    }

    public OffsetDateTime getExpectedDelivery() {
        return expectedDelivery;
    }

    public String getNotes() {
        return notes;
    }

    public String getFlag() {
        return flag;
    }

    public List<FashionOrderItem> getItems() {
        return items;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public void setExpectedDelivery(OffsetDateTime expectedDelivery) {
        this.expectedDelivery = expectedDelivery;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public void setFlag(String flag) {
        this.flag = flag;
    }

    public void setItems(List<FashionOrderItem> items) {
        this.items = items;
    }

    /** Inner class for fashion order item with size/color selection. */
    @Embeddable
    public static class FashionOrderItem {
        @Column(name = "shoe_id")
        private UUID shoeId;

        @Column(name = "shoe_name")
        private String shoeName;

        @Column(name = "size")
        private String size;

        @Column(name = "color")
        private String color;

        @Column(name = "quantity")
        private int quantity;

        @Column(name = "unit_price")
        private BigDecimal unitPrice;

        @Column(name = "total_price")
        private BigDecimal totalPrice;

        protected FashionOrderItem() {
        }

        public FashionOrderItem(UUID shoeId, String shoeName, String size, String color,
                                int quantity, BigDecimal unitPrice) {
            this.shoeId = shoeId;
            this.shoeName = shoeName;
            this.size = size;
            this.color = color;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.totalPrice = unitPrice.multiply(BigDecimal.valueOf(quantity));
        }

        public UUID getShoeId() {
            return shoeId;
        }

        public String getShoeName() {
            return shoeName;
        }

        public String getSize() {
            return size;
        }

        public String getColor() {
            return color;
        }

        public int getQuantity() {
            return quantity;
        }

        public BigDecimal getUnitPrice() {
            return unitPrice;
        }

        public BigDecimal getTotalPrice() {
            return totalPrice;
        }
    }
}
