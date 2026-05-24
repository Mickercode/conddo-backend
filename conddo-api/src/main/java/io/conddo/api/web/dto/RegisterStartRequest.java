package io.conddo.api.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Signup step 1 — account details. Triggers the SMS OTP (PRD §6.2). */
public record RegisterStartRequest(
        @NotBlank String fullName,
        @NotBlank @Size(min = 7, max = 20) String phone,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, message = "password must be at least 8 characters") String password
) {
}
