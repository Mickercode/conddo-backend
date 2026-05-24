package io.conddo.api.web.dto;

import java.math.BigDecimal;

/** PATCH a lead (§11.8): move funnel stage and/or edit fields. All optional. */
public record UpdateLeadRequest(String stage, String name, BigDecimal value, String notes) {
}
