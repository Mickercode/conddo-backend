package io.conddo.api.web.dto;

import java.math.BigDecimal;

/** PATCH a line item (§11.4). All optional — only sent fields change. */
public record UpdateItemRequest(
        String description,
        Integer quantity,
        BigDecimal unitPrice
) {
}
