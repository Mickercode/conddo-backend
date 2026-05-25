package io.conddo.studio.web.dto;

import io.conddo.studio.domain.Staff;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** A staff member's profile (Jobs Board). */
public record StaffDto(
        UUID id,
        String name,
        String email,
        String role,
        List<String> skills,
        boolean active,
        OffsetDateTime lastActive
) {
    public static StaffDto from(Staff s) {
        return new StaffDto(s.getId(), s.getFullName(), s.getEmail(), s.getRole(),
                s.getSkills(), s.isActive(), s.getLastLoginAt());
    }
}
