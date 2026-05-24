package io.conddo.api.web.dto;

import io.conddo.core.domain.MarketingLead;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A marketing lead (§11.8). */
public record LeadDto(
        UUID id,
        String name,
        String email,
        String phone,
        String source,
        String stage,
        BigDecimal value,
        String notes,
        OffsetDateTime createdAt
) {
    public static LeadDto from(MarketingLead l) {
        return new LeadDto(l.getId(), l.getName(), l.getEmail(), l.getPhone(), l.getSource(),
                l.getStage(), l.getValue(), l.getNotes(), l.getCreatedAt());
    }
}
