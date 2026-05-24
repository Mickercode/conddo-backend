package io.conddo.api.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Invite a staff member (§11.10). {@code role} is TENANT_ADMIN or STAFF. */
public record InviteStaffRequest(
        @NotBlank @Email String email,
        @NotBlank String role
) {
}
