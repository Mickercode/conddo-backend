package io.conddo.api.web.dto;

import io.conddo.core.domain.Notification;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A bell-feed notification (§11.12). */
public record NotificationDto(
        UUID id,
        String type,
        String title,
        String body,
        boolean read,
        OffsetDateTime at
) {
    public static NotificationDto from(Notification n) {
        return new NotificationDto(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.isRead(), n.getCreatedAt());
    }
}
