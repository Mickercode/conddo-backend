package io.conddo.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row per tenant — their RoutePay sub-account record (§7a). Created by an
 * @Async listener on {@code TenantActivatedEvent} (conddo-api side) → POST
 * /api/payments/internal/tenants on this service → RoutePay sub-account create.
 *
 * <p>Settlement bank details may be null at signup (we don't ask upfront); the
 * tenant fills them in Settings → Payments. Sub-account exists in
 * {@code DEPOSIT_PENDING} until then — RoutePay still accepts payments, it
 * just holds the balance until payouts are configured.
 */
@Entity
@Table(name = "tenant_accounts")
public class TenantAccount {

    @Id
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "tenant_slug", nullable = false)
    private String tenantSlug;

    @Column(name = "routepay_subaccount_id", unique = true)
    private String routepaySubaccountId;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(name = "contact_email", nullable = false)
    private String contactEmail;

    @Column(name = "settlement_bank_name")
    private String settlementBankName;

    @Column(name = "settlement_bank_account")
    private String settlementBankAccount;

    @Column(name = "settlement_account_holder")
    private String settlementAccountHolder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantAccountStatus status = TenantAccountStatus.DEPOSIT_PENDING;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected TenantAccount() {
    }

    public TenantAccount(UUID tenantId, String tenantSlug, String businessName, String contactEmail) {
        this.tenantId = tenantId;
        this.tenantSlug = tenantSlug;
        this.businessName = businessName;
        this.contactEmail = contactEmail;
    }

    /** Mark the sub-account as live (called once RoutePay returns the sub-account id). */
    public void activate(String routepaySubaccountId) {
        this.routepaySubaccountId = routepaySubaccountId;
        this.status = TenantAccountStatus.ACTIVE;
    }

    /** Provisioning attempt failed — record so the manual retry endpoint knows what to retry. */
    public void markProvisioningFailed() {
        this.status = TenantAccountStatus.PROVISIONING_FAILED;
    }

    /** Tenant added bank details in Settings → Payments. */
    public void setSettlementBank(String bankName, String bankAccount, String accountHolder) {
        this.settlementBankName = bankName;
        this.settlementBankAccount = bankAccount;
        this.settlementAccountHolder = accountHolder;
    }

    public void suspend() {
        this.status = TenantAccountStatus.SUSPENDED;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getTenantSlug() {
        return tenantSlug;
    }

    public String getRoutepaySubaccountId() {
        return routepaySubaccountId;
    }

    public String getBusinessName() {
        return businessName;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getSettlementBankName() {
        return settlementBankName;
    }

    public String getSettlementBankAccount() {
        return settlementBankAccount;
    }

    public String getSettlementAccountHolder() {
        return settlementAccountHolder;
    }

    public TenantAccountStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
