package io.conddo.api.web.dto;

import io.conddo.core.domain.MarketingPost;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A social post for the calendar/list (§11.8). {@code platform} is the primary
 * (first) target; {@code platforms} is the full set.
 */
public record MarketingPostDto(
        UUID id,
        String title,
        String platform,
        List<String> platforms,
        String content,
        List<String> mediaIds,
        OffsetDateTime scheduledAt,
        String status,
        OffsetDateTime publishedAt
) {
    public static MarketingPostDto from(MarketingPost p) {
        return new MarketingPostDto(p.getId(), p.getTitle(), p.primaryPlatform(), p.getPlatforms(),
                p.getContent(), p.getMediaIds(), p.getScheduledAt(), p.getStatus(), p.getPublishedAt());
    }
}
