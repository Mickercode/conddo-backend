package io.conddo.studio.web.dto;

import io.conddo.studio.platform.SiteAuditLog;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SiteAuditEntryDto(
        UUID id,
        UUID siteId,
        String action,
        UUID byStaffId,
        String byStaffName,
        String detail,
        OffsetDateTime at) {

    public static SiteAuditEntryDto of(SiteAuditLog row, String byStaffName) {
        return new SiteAuditEntryDto(
                row.getId(), row.getSiteId(), row.getAction(),
                row.getByStaffId(), byStaffName, row.getDetail(), row.getCreatedAt());
    }
}
