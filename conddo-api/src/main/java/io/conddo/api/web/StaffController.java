package io.conddo.api.web;

import io.conddo.api.web.dto.InviteStaffRequest;
import io.conddo.api.web.dto.StaffActivityDto;
import io.conddo.api.web.dto.StaffRow;
import io.conddo.api.web.dto.UpdateStaffRequest;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.StaffService;
import io.conddo.core.service.StaffService.RoleDef;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * Staff management (§11.10) — admin only. The tenant's users over the existing
 * RLS-scoped {@code users} table; tenant comes from the JWT. Every endpoint is
 * restricted to TENANT_ADMIN (or an acting SUPER_ADMIN).
 */
@RestController
@RequestMapping("/api/v1/staff")
@PreAuthorize("hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')")
public class StaffController {

    private final StaffService staffService;

    public StaffController(StaffService staffService) {
        this.staffService = staffService;
    }

    @GetMapping
    public ApiResponse<List<StaffRow>> list() {
        return ApiResponse.ok(staffService.list().stream().map(StaffRow::from).toList());
    }

    @PostMapping("/invite")
    public ResponseEntity<ApiResponse<StaffRow>> invite(@Valid @RequestBody InviteStaffRequest request) {
        StaffRow body = StaffRow.from(staffService.invite(request.email(), request.role()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    @GetMapping("/roles")
    public ApiResponse<List<RoleDef>> roles() {
        return ApiResponse.ok(staffService.roles());
    }

    @GetMapping("/{id}")
    public ApiResponse<StaffRow> get(@PathVariable UUID id) {
        return ApiResponse.ok(StaffRow.from(staffService.get(id)));
    }

    @PatchMapping("/{id}")
    public ApiResponse<StaffRow> update(@PathVariable UUID id, @RequestBody UpdateStaffRequest request) {
        return ApiResponse.ok(StaffRow.from(staffService.update(id, request.role(), request.active())));
    }

    @PostMapping("/{id}/resend-invite")
    public ResponseEntity<Void> resendInvite(@PathVariable UUID id) {
        staffService.resendInvite(id);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{id}/activity")
    public ApiResponse<List<StaffActivityDto>> activity(@PathVariable UUID id) {
        return ApiResponse.ok(staffService.activity(id).stream().map(StaffActivityDto::from).toList());
    }
}
