package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "pharmacy_program_items")
public class PharmacyProgramItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "program_id", nullable = false)
    private UUID programId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private int quantity = 1;

    @Column(nullable = false, length = 20)
    private String frequency = "MONTHLY";

    protected PharmacyProgramItem() {
    }

    public PharmacyProgramItem(UUID tenantId, UUID programId, UUID productId,
                                int quantity, String frequency) {
        this.tenantId = tenantId;
        this.programId = programId;
        this.productId = productId;
        this.quantity = quantity;
        this.frequency = frequency;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getProgramId() {
        return programId;
    }

    public UUID getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getFrequency() {
        return frequency;
    }
}
