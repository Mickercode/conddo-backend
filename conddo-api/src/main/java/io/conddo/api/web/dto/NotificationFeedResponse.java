package io.conddo.api.web.dto;

import io.conddo.core.service.NotificationFeedService.Feed;

import java.util.List;

/** The bell feed (§11.12): recent notifications + the unread badge count. */
public record NotificationFeedResponse(List<NotificationDto> items, long unread) {

    public static NotificationFeedResponse from(Feed feed) {
        return new NotificationFeedResponse(
                feed.items().stream().map(NotificationDto::from).toList(), feed.unread());
    }
}
