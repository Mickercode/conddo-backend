package io.conddo.payments.web.dto;

import io.conddo.payments.domain.TenantAccount;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Tenant's RoutePay sub-account state. {@code settlementBankAccount} is masked
 * at read time — only the last 4 digits leak out — so the dashboard can show
 * "···6789" without exposing the full NUBAN.
 */
public record TenantAccountResponse(UUID tenantId,
                                    String tenantSlug,
                                    String businessName,
                                    String contactEmail,
                                    String routepaySubaccountId,
                                    String settlementBankName,
                                    String settlementBankAccountMasked,
                                    String settlementAccountHolder,
                                    String status,
                                    OffsetDateTime createdAt,
                                    OffsetDateTime updatedAt) {

    public static TenantAccountResponse from(TenantAccount a) {
        return new TenantAccountResponse(a.getTenantId(), a.getTenantSlug(),
                a.getBusinessName(), a.getContactEmail(), a.getRoutepaySubaccountId(),
                a.getSettlementBankName(), maskAccount(a.getSettlementBankAccount()),
                a.getSettlementAccountHolder(), a.getStatus().name(),
                a.getCreatedAt(), a.getUpdatedAt());
    }

    private static String maskAccount(String raw) {
        if (raw == null || raw.length() < 4) {
            return raw;
        }
        return "···" + raw.substring(raw.length() - 4);
    }
}
