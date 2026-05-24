package io.conddo.api.web.dto;

import io.conddo.core.domain.MarketingConnection;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A connected social account (§11.8). */
public record ConnectionDto(UUID id, String platform, String handle, String status, OffsetDateTime connectedAt) {

    public static ConnectionDto from(MarketingConnection c) {
        return new ConnectionDto(c.getId(), c.getPlatform(), c.getHandle(), c.getStatus(), c.getConnectedAt());
    }
}
