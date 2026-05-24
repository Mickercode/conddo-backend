package io.conddo.api.web.dto;

import io.conddo.core.domain.MarketingCampaign;

import java.time.OffsetDateTime;
import java.util.UUID;

/** An email/SMS campaign with its delivery stats (§11.8). */
public record CampaignDto(
        UUID id,
        String name,
        String type,
        String status,
        int audienceSize,
        int sent,
        int delivered,
        int opened,
        int clicked,
        double openRate,
        double clickRate,
        OffsetDateTime scheduledAt
) {
    public static CampaignDto from(MarketingCampaign c) {
        return new CampaignDto(c.getId(), c.getName(), c.getType(), c.getStatus(), c.getAudienceSize(),
                c.getSent(), c.getDelivered(), c.getOpened(), c.getClicked(),
                c.openRate(), c.clickRate(), c.getScheduledAt());
    }
}
