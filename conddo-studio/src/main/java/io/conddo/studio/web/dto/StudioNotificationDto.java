package io.conddo.studio.web.dto;

import io.conddo.studio.domain.StaffNotification;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A staff in-app notification. */
public record StudioNotificationDto(
        UUID id,
        String type,
        String title,
        String message,
        UUID jobId,
        boolean read,
        OffsetDateTime at
) {
    public static StudioNotificationDto from(StaffNotification n) {
        return new StudioNotificationDto(n.getId(), n.getType(), n.getTitle(), n.getMessage(),
                n.getJobId(), n.isRead(), n.getCreatedAt());
    }
}
