package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One issuance of a {@link PharmacyRefillOffer} to a specific
 * customer. The {@code offer_code} is a short uppercase string the
 * customer presents at checkout (Pharmacy Spec v2 §12E). Once
 * redeemed, {@code used_at} + {@code order_id} are stamped and the
 * code can't be re-used.
 */
@Entity
@Table(name = "pharmacy_refill_offer_claims")
public class PharmacyRefillOfferClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "offer_id", nullable = false)
    private UUID offerId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "offer_code", nullable = false, unique = true, length = 40)
    private String offerCode;

    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @Column(name = "order_id")
    private UUID orderId;

    protected PharmacyRefillOfferClaim() {
    }

    public PharmacyRefillOfferClaim(UUID tenantId, UUID offerId, UUID customerId,
                                    String offerCode, OffsetDateTime issuedAt,
                                    OffsetDateTime expiresAt) {
        this.tenantId = tenantId;
        this.offerId = offerId;
        this.customerId = customerId;
        this.offerCode = offerCode;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getOfferId() {
        return offerId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getOfferCode() {
        return offerCode;
    }

    public OffsetDateTime getIssuedAt() {
        return issuedAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getUsedAt() {
        return usedAt;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public boolean isExpiredAt(OffsetDateTime when) {
        return when.isAfter(expiresAt);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public void markUsed(OffsetDateTime at, UUID orderId) {
        this.usedAt = at;
        this.orderId = orderId;
    }
}
