package io.conddo.api.web;

import io.conddo.api.web.dto.VerticalConfigResponse;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.vertical.VerticalConfigRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/verticals/{id}/config} — reference config a vertical's
 * dashboard uses for order stages, measurement fields, and website sections
 * (§11.12). Authenticated; the config is global per vertical (not tenant data).
 */
@RestController
@RequestMapping("/api/v1/verticals")
public class VerticalController {

    private final VerticalConfigRegistry registry;

    public VerticalController(VerticalConfigRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/{id}/config")
    public ApiResponse<VerticalConfigResponse> config(@PathVariable String id) {
        return ApiResponse.ok(VerticalConfigResponse.from(registry.require(id)));
    }
}
