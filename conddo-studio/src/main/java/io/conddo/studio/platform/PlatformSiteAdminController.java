package io.conddo.studio.platform;

import io.conddo.studio.auth.StudioPrincipal;
import io.conddo.studio.common.ApiResponse;
import io.conddo.studio.domain.Staff;
import io.conddo.studio.web.dto.PatchSiteRequest;
import io.conddo.studio.web.dto.PlatformSiteDto;
import io.conddo.studio.web.dto.QaActionRequest;
import io.conddo.studio.web.dto.RegisterKeyResponse;
import io.conddo.studio.web.dto.RegisterSiteRequest;
import io.conddo.studio.web.dto.SiteAuditEntryDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Studio Platform Admin — Site Registration
 * (SITE_REGISTRATION_ADMIN_SPEC §3). Lets ops register tenant sites,
 * rotate API keys, QA-approve, and edit metadata from a UI instead of
 * raw SQL. Replaces the manual provisioning step the Seb&Bayor onboarding
 * (and every future tenant integration) was blocked on.
 *
 * <p>Role guards mirror the spec §2 table:
 * <ul>
 *   <li>SUPER_ADMIN — everything</li>
 *   <li>TEAM_LEAD — read-only (list, detail, audit)</li>
 *   <li>QA_REVIEWER — reads + qa-approve/qa-revoke</li>
 *   <li>Other roles — 403 on every route</li>
 * </ul>
 *
 * <p>Spec maps "SUPER_ADMIN" to Studio's existing {@code ADMIN} role;
 * Studio doesn't distinguish a separate super-admin tier. {@code STAFF}
 * users with the {@code QA_REVIEWER} skill flag use the QA action.
 */
@RestController
@RequestMapping("/api/jobs/admin/platform/sites")
@PreAuthorize("hasAnyRole('ADMIN','TEAM_LEAD','QA_REVIEWER')")
public class PlatformSiteAdminController {

    private static final String ADMIN = "hasRole('ADMIN')";
    private static final String ADMIN_OR_QA = "hasAnyRole('ADMIN','QA_REVIEWER')";

    private final PlatformSiteAdminService service;

    public PlatformSiteAdminController(PlatformSiteAdminService service) {
        this.service = service;
    }

    // ----- reads -------------------------------------------------------------

    @GetMapping
    public ApiResponse<List<PlatformSiteDto>> list() {
        List<PlatformTenantSite> rows = service.list();
        Map<UUID, PlatformTenant> tenantCache = new HashMap<>();
        Map<UUID, Staff> staffCache = new HashMap<>();
        return ApiResponse.ok(rows.stream()
                .map(s -> PlatformSiteDto.of(s,
                        resolveTenant(s.getTenantId(), tenantCache),
                        resolveStaffName(s.getQaApprovedBy(), staffCache)))
                .toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<PlatformSiteDto> get(@PathVariable UUID id) {
        PlatformTenantSite site = service.get(id);
        return ApiResponse.ok(toDto(site));
    }

    @GetMapping("/{id}/audit")
    public ApiResponse<List<SiteAuditEntryDto>> audit(@PathVariable UUID id) {
        Map<UUID, Staff> staffCache = new HashMap<>();
        return ApiResponse.ok(service.auditLog(id).stream()
                .map(row -> SiteAuditEntryDto.of(row,
                        resolveStaffName(row.getByStaffId(), staffCache)))
                .toList());
    }

    // ----- writes ------------------------------------------------------------

    @PostMapping
    @PreAuthorize(ADMIN)
    public ResponseEntity<ApiResponse<RegisterKeyResponse>> register(
            @Valid @RequestBody RegisterSiteRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        PlatformSiteAdminService.RegisterResult result = service.register(
                StudioPrincipal.staffId(jwt),
                body.tenantId(), body.subdomain(), body.customDomain(),
                body.hostingProvider(), body.siteType(), body.submittedUrl());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                new RegisterKeyResponse(toDto(result.site()), result.plaintextKey())));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(ADMIN)
    public ApiResponse<PlatformSiteDto> patch(@PathVariable UUID id,
                                              @Valid @RequestBody PatchSiteRequest body,
                                              @AuthenticationPrincipal Jwt jwt) {
        PlatformTenantSite updated = service.patchMetadata(
                StudioPrincipal.staffId(jwt), id,
                body.subdomain(), body.customDomain(), body.hostingProvider(),
                body.siteType(), body.submittedUrl());
        return ApiResponse.ok(toDto(updated));
    }

    @PostMapping("/{id}/rotate-key")
    @PreAuthorize(ADMIN)
    public ApiResponse<RegisterKeyResponse> rotateKey(@PathVariable UUID id,
                                                      @AuthenticationPrincipal Jwt jwt) {
        PlatformSiteAdminService.RegisterResult result = service.rotateKey(
                StudioPrincipal.staffId(jwt), id);
        return ApiResponse.ok(new RegisterKeyResponse(toDto(result.site()), result.plaintextKey()));
    }

    @PostMapping("/{id}/qa-approve")
    @PreAuthorize(ADMIN_OR_QA)
    public ApiResponse<PlatformSiteDto> qaApprove(@PathVariable UUID id,
                                                  @RequestBody(required = false) QaActionRequest body,
                                                  @AuthenticationPrincipal Jwt jwt) {
        String note = body == null ? null : body.note();
        return ApiResponse.ok(toDto(service.qaApprove(StudioPrincipal.staffId(jwt), id, note)));
    }

    @PostMapping("/{id}/qa-revoke")
    @PreAuthorize(ADMIN_OR_QA)
    public ApiResponse<PlatformSiteDto> qaRevoke(@PathVariable UUID id,
                                                 @RequestBody(required = false) QaActionRequest body,
                                                 @AuthenticationPrincipal Jwt jwt) {
        String note = body == null ? null : body.note();
        return ApiResponse.ok(toDto(service.qaRevoke(StudioPrincipal.staffId(jwt), id, note)));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize(ADMIN)
    public ApiResponse<PlatformSiteDto> activate(@PathVariable UUID id,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(toDto(service.activate(StudioPrincipal.staffId(jwt), id)));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize(ADMIN)
    public ApiResponse<PlatformSiteDto> deactivate(@PathVariable UUID id,
                                                   @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(toDto(service.deactivate(StudioPrincipal.staffId(jwt), id)));
    }

    // ----- helpers -----------------------------------------------------------

    private PlatformSiteDto toDto(PlatformTenantSite site) {
        PlatformTenant tenant = service.tenantOf(site.getTenantId()).orElse(null);
        String qaName = resolveStaffName(site.getQaApprovedBy(), new HashMap<>());
        return PlatformSiteDto.of(site, tenant, qaName);
    }

    private PlatformTenant resolveTenant(UUID tenantId, Map<UUID, PlatformTenant> cache) {
        if (tenantId == null) {
            return null;
        }
        return cache.computeIfAbsent(tenantId, id -> service.tenantOf(id).orElse(null));
    }

    private String resolveStaffName(UUID staffId, Map<UUID, Staff> cache) {
        if (staffId == null) {
            return null;
        }
        Staff cached = cache.get(staffId);
        if (cached != null) {
            return cached.getFullName();
        }
        Optional<Staff> staff = service.staffById(staffId);
        staff.ifPresent(s -> cache.put(staffId, s));
        return staff.map(Staff::getFullName).orElse(null);
    }
}
