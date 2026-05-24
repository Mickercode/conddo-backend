package io.conddo.api.web.dto;

import io.conddo.core.domain.User;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A staff member (§11.10). {@code status} is derived: an inactive account is
 * "inactive"; an active one that has never logged in is "invited"; otherwise "active".
 */
public record StaffRow(
        UUID id,
        String name,
        String email,
        String role,
        String status,
        OffsetDateTime lastActive
) {
    public static StaffRow from(User u) {
        String status = !u.isActive() ? "inactive" : (u.getLastLoginAt() == null ? "invited" : "active");
        return new StaffRow(u.getId(), u.getFullName(), u.getEmail(), u.getRole(), status, u.getLastLoginAt());
    }
}
