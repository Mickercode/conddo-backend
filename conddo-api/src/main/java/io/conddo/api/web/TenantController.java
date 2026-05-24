package io.conddo.api.web;

import io.conddo.api.web.dto.CreateTenantRequest;
import io.conddo.api.web.dto.TenantResponse;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.TenantService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Tenant signup / listing (PRD §13.1 — POST /tenants). Not tenant-scoped: this
 * is what creates tenants. Listing all tenants is a super-admin concern,
 * exposed here for the Phase 0 demo.
 */
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TenantResponse>> create(@Valid @RequestBody CreateTenantRequest request) {
        TenantResponse body = TenantResponse.from(tenantService.create(
                request.name(), request.slug(), request.verticalId(), request.planId(),
                request.adminEmail(), request.adminPassword(), request.adminFullName()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    @GetMapping
    public ApiResponse<List<TenantResponse>> list() {
        List<TenantResponse> items = tenantService.findAll().stream()
                .map(TenantResponse::from)
                .toList();
        return ApiResponse.ok(items, ApiResponse.Meta.total(items.size()));
    }
}
