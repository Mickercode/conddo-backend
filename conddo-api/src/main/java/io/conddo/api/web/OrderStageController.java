package io.conddo.api.web;

import io.conddo.api.web.dto.StageDto;
import io.conddo.api.web.dto.StageRequest;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.OrderStageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Manage the tenant's order pipeline stages (§11.4). A tenant with no overrides
 * sees its vertical's default stages; the first create/edit materialises those
 * defaults so they can be renamed, reordered, or removed.
 */
@RestController
@RequestMapping("/api/v1/orders/stages")
public class OrderStageController {

    private static final String READ = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";
    private static final String WRITE = "hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')";

    private final OrderStageService stageService;

    public OrderStageController(OrderStageService stageService) {
        this.stageService = stageService;
    }

    @GetMapping
    @PreAuthorize(READ)
    public ApiResponse<List<StageDto>> list() {
        return ApiResponse.ok(stageService.list().stream().map(StageDto::from).toList());
    }

    @PostMapping
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<StageDto>> create(@RequestBody StageRequest request) {
        StageDto body = StageDto.from(toView(stageService.add(request.name(), request.position())));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(WRITE)
    public ApiResponse<StageDto> update(@PathVariable UUID id, @RequestBody StageRequest request) {
        return ApiResponse.ok(StageDto.from(toView(stageService.update(id, request.name(), request.position()))));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        stageService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private static OrderStageService.StageView toView(io.conddo.core.domain.OrderStage s) {
        return new OrderStageService.StageView(s.getId(), s.getName(), s.getPosition());
    }
}
