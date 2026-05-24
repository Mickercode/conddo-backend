package io.conddo.api.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** Schedule a social post (§11.8). */
public record CreatePostRequest(
        String title,
        String content,
        List<String> platforms,
        List<String> mediaIds,
        OffsetDateTime scheduledAt
) {
}
