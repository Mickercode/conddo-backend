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
import java.util.UUID;

/**
 * A customer-submitted prescription uploaded from the merchant's public
 * pharmacy website (PHARMACY_PUBLIC_API_SPEC §7). Distinct from
 * {@link Prescription} — that's the merchant's own internal dispensing
 * log. This row sits in the pharmacist's review queue until they approve
 * or reject it; an approved row can be linked to a created {@code Order}
 * via {@code orderId}.
 */
@Entity
@Table(name = "customer_prescriptions")
public class CustomerPrescription {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(name = "file_url", nullable = false)
    private String fileUrl;

    @Column(name = "patient_name", nullable = false)
    private String patientName;

    @Column(name = "prescriber_name", nullable = false)
    private String prescriberName;

    private String notes;

    @Column(nullable = false)
    private String status = STATUS_PENDING;

    @Column(name = "review_note")
    private String reviewNote;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_by_name")
    private String reviewedByName;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "submitted_at", nullable = false)
    private OffsetDateTime submittedAt = OffsetDateTime.now();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected CustomerPrescription() {
    }

    public CustomerPrescription(UUID tenantId, UUID customerId, String customerName,
                                String customerPhone, String fileUrl, String patientName,
                                String prescriberName, String notes) {
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.customerName = customerName;
        this.customerPhone = customerPhone;
        this.fileUrl = fileUrl;
        this.patientName = patientName;
        this.prescriberName = prescriberName;
        this.notes = notes;
    }

    /** Pharmacist approves or rejects the submission. */
    public void review(String newStatus, String reviewNote, UUID reviewerId,
                       String reviewerName, OffsetDateTime at) {
        if (!STATUS_APPROVED.equals(newStatus) && !STATUS_REJECTED.equals(newStatus)) {
            throw new IllegalArgumentException(
                    "Review status must be APPROVED or REJECTED, got: " + newStatus);
        }
        this.status = newStatus;
        this.reviewNote = reviewNote;
        this.reviewedBy = reviewerId;
        this.reviewedByName = reviewerName;
        this.reviewedAt = at;
    }

    public void linkOrder(UUID orderId) {
        this.orderId = orderId;
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

    public String getCustomerPhone() {
        return customerPhone;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public String getPatientName() {
        return patientName;
    }

    public String getPrescriberName() {
        return prescriberName;
    }

    public String getNotes() {
        return notes;
    }

    public String getStatus() {
        return status;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public OffsetDateTime getReviewedAt() {
        return reviewedAt;
    }

    public UUID getReviewedBy() {
        return reviewedBy;
    }

    public String getReviewedByName() {
        return reviewedByName;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public OffsetDateTime getSubmittedAt() {
        return submittedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
