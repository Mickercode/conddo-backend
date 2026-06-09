package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pharmacist-defined refill offer for a single product (Pharmacy Spec
 * v2 §12E). A pharmacist instantiates one per product, then "issues"
 * it to specific customers after their order is dispensed — each
 * issuance creates a {@link PharmacyRefillOfferClaim} with a unique
 * short code and an expires_at = issued_at + valid_days.
 */
@Entity
@Table(name = "pharmacy_refill_offers")
public class PharmacyRefillOffer {

    public static final String TYPE_PERCENTAGE = "PERCENTAGE";
    public static final String TYPE_FIXED = "FIXED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "discount_type", nullable = false, length = 20)
    private String discountType;

    @Column(name = "discount_value", nullable = false)
    private BigDecimal discountValue;

    @Column(name = "valid_days", nullable = false)
    private int validDays;

    @Column(name = "max_uses", nullable = false)
    private int maxUses = 1;

    private String message;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected PharmacyRefillOffer() {
    }

    public PharmacyRefillOffer(UUID tenantId, UUID productId, String discountType,
                               BigDecimal discountValue, int validDays, int maxUses,
                               String message, UUID createdBy) {
        this.tenantId = tenantId;
        this.productId = productId;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.validDays = validDays;
        this.maxUses = maxUses;
        this.message = message;
        this.createdBy = createdBy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getDiscountType() {
        return discountType;
    }

    public BigDecimal getDiscountValue() {
        return discountValue;
    }

    public int getValidDays() {
        return validDays;
    }

    public int getMaxUses() {
        return maxUses;
    }

    public String getMessage() {
        return message;
    }

    public boolean isActive() {
        return active;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void deactivate() {
        this.active = false;
    }

    /** Apply the offer's discount to a list price; mirrors PharmacyDiscount.applyTo. */
    public BigDecimal applyTo(BigDecimal price) {
        if (price == null) {
            return null;
        }
        BigDecimal discounted = switch (discountType) {
            case TYPE_PERCENTAGE -> price.subtract(price.multiply(discountValue)
                    .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
            case TYPE_FIXED -> price.subtract(discountValue);
            default -> price;
        };
        if (discounted.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return discounted.setScale(2, RoundingMode.HALF_UP);
    }
}
