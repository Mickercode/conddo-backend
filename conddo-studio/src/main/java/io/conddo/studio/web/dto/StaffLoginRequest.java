package io.conddo.studio.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Studio staff login. */
public record StaffLoginRequest(@NotBlank @Email String email, @NotBlank String password) {
}
