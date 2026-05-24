package io.conddo.api.web.dto;

/** PATCH a staff member (§11.10): change role and/or activate/deactivate. Both optional. */
public record UpdateStaffRequest(String role, Boolean active) {
}
