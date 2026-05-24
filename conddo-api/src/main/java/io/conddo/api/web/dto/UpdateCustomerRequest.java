package io.conddo.api.web.dto;

import jakarta.validation.constraints.Email;

/** PATCH customer contact fields (§11.3). All optional — only sent fields change. */
public record UpdateCustomerRequest(
        String fullName,
        @Email String email,
        String phone
) {
}
