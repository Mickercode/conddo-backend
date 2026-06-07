package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * A customer-requested pharmacy consultation from the merchant's public
 * website (PHARMACY_PUBLIC_API_SPEC §8). Customers tend to leave a
 * WhatsApp number — the pharmacist reaches out via that channel.
 */
@Entity
@Table(name = "consultations")
public class Consultation {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private static final Set<String> VALID_STATUSES = Set.of(
            STATUS_PENDING, STATUS_CONFIRMED, STATUS_COMPLETED, STATUS_CANCELLED);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "whatsapp_number", nullable = false)
    private String whatsappNumber;

    @Column(nullable = false)
    private String topic;

    @Column(name = "preferred_time")
    private String preferredTime;

    @Column(nullable = false)
    private String status = STATUS_PENDING;

    @Column(name = "pharmacist_note")
    private String pharmacistNote;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected Consultation() {
    }

    public Consultation(UUID tenantId, UUID customerId, String customerName,
                        String whatsappNumber, String topic, String preferredTime) {
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.customerName = customerName;
        this.whatsappNumber = whatsappNumber;
        this.topic = topic;
        this.preferredTime = preferredTime;
    }

    /** Pharmacist updates the consultation state. {@code completedAt} stamped on COMPLETED. */
    public void updateStatus(String newStatus, String pharmacistNote, OffsetDateTime at) {
        if (newStatus == null || !VALID_STATUSES.contains(newStatus)) {
            throw new IllegalArgumentException(
                    "Status must be one of " + VALID_STATUSES + ", got: " + newStatus);
        }
        this.status = newStatus;
        if (pharmacistNote != null) {
            this.pharmacistNote = pharmacistNote;
        }
        if (STATUS_COMPLETED.equals(newStatus)) {
            this.completedAt = at;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getWhatsappNumber() {
        return whatsappNumber;
    }

    public String getTopic() {
        return topic;
    }

    public String getPreferredTime() {
        return preferredTime;
    }

    public String getStatus() {
        return status;
    }

    public String getPharmacistNote() {
        return pharmacistNote;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
