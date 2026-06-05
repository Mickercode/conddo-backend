package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Body for {@code POST /billing/upgrade}. {@code billingCycle} optional — defaults to monthly. */
public record UpgradeRequest(@NotBlank String planId, String billingCycle) {
}
