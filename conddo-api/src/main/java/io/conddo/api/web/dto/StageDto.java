package io.conddo.api.web.dto;

import io.conddo.core.service.OrderStageService.StageView;

import java.util.UUID;

/**
 * A pipeline stage (§11.4). {@code id} is null for an unmaterialised vertical
 * default (it gets one once the tenant first edits the pipeline).
 */
public record StageDto(UUID id, String name, int position) {

    public static StageDto from(StageView s) {
        return new StageDto(s.id(), s.name(), s.position());
    }
}
