package io.conddo.studio.web.dto;

import io.conddo.studio.domain.StaffNotification;

import java.util.List;

/** The staff notifications feed: items + unread count. */
public record NotificationFeedResponse(List<StudioNotificationDto> items, long unread) {

    public static NotificationFeedResponse of(List<StaffNotification> items, long unread) {
        return new NotificationFeedResponse(items.stream().map(StudioNotificationDto::from).toList(), unread);
    }
}
