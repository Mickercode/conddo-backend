package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

/** Create a campaign (§11.8). {@code type} is email or sms; starts as a draft. */
public record CreateCampaignRequest(
        @NotBlank String name,
        @NotBlank String type,
        String content,
        Integer audienceSize,
        OffsetDateTime scheduledAt
) {
}
