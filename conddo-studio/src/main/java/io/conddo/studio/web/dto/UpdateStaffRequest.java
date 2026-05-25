package io.conddo.studio.web.dto;

/** Change a staff member's role and/or active state (admin). */
public record UpdateStaffRequest(String role, Boolean active) {
}
