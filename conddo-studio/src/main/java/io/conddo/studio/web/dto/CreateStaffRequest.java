package io.conddo.studio.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** Add a Studio staff member (admin). */
public record CreateStaffRequest(
        @NotBlank @Email String email,
        @NotBlank String fullName,
        @NotBlank String role,
        List<String> skills,
        @NotBlank String password
) {
}
