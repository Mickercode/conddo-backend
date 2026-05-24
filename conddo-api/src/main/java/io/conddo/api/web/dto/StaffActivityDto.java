package io.conddo.api.web.dto;

import io.conddo.core.domain.AuditLog;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A staff member's recorded action (§11.10), from the audit log. */
public record StaffActivityDto(
        UUID id,
        String action,
        String resourceType,
        String ipAddress,
        OffsetDateTime at
) {
    public static StaffActivityDto from(AuditLog a) {
        return new StaffActivityDto(a.getId(), a.getAction(), a.getResourceType(), a.getIpAddress(), a.getCreatedAt());
    }
}
