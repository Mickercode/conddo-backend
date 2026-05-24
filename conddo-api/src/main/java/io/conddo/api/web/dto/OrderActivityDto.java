package io.conddo.api.web.dto;

import io.conddo.core.domain.OrderActivity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** An entry in an order's activity log (§11.4): transitions, payments, messages. */
public record OrderActivityDto(
        UUID id,
        String type,
        String title,
        String detail,
        String actor,
        OffsetDateTime at
) {
    public static OrderActivityDto from(OrderActivity a) {
        return new OrderActivityDto(a.getId(), a.getType(), a.getTitle(), a.getDetail(),
                a.getActor(), a.getCreatedAt());
    }
}
