package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Create an inventory category (§11.6). */
public record CreateCategoryRequest(@NotBlank String name) {
}
