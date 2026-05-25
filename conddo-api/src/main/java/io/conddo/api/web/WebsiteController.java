package io.conddo.api.web;

import io.conddo.api.web.dto.ConnectDomainBody;
import io.conddo.api.web.dto.WebsiteChangeRequestBody;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.WebsiteService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Website module (§11.2). The tenant side is read + request-changes — the site
 * is built in Conddo Studio (§8). Reads are open to any staff role; requesting
 * an edit or connecting a domain is a {@code TENANT_ADMIN} action. The owner's
 * tenant comes from the JWT and is enforced by RLS / {@code TenantContext}.
 */
@RestController
@RequestMapping("/api/v1/website")
public class WebsiteController {

    private static final String READ = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";
    private static final String WRITE = "hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')";

    private final WebsiteService websiteService;

    public WebsiteController(WebsiteService websiteService) {
        this.websiteService = websiteService;
    }

    /** Site config: {@code {subdomain, customDomain, status, publishedAt}}. */
    @GetMapping
    @PreAuthorize(READ)
    public ApiResponse<WebsiteService.Site> site() {
        return ApiResponse.ok(websiteService.site());
    }

    /** Status widget: {@code {state, domain, visitsToday, enquiries}} (reused by the dashboard). */
    @GetMapping("/status")
    @PreAuthorize(READ)
    public ApiResponse<WebsiteService.LiveStatus> status() {
        return ApiResponse.ok(websiteService.status());
    }

    /** Configured sections (read-only snapshot). */
    @GetMapping("/sections")
    @PreAuthorize(READ)
    public ApiResponse<List<WebsiteService.Section>> sections() {
        return ApiResponse.ok(websiteService.sections());
    }

    /** Visits, enquiries, and top pages over a range. */
    @GetMapping("/analytics")
    @PreAuthorize(READ)
    public ApiResponse<WebsiteService.Analytics> analytics(@RequestParam(required = false) String range) {
        return ApiResponse.ok(websiteService.analytics(range));
    }

    /** The tenant's edit requests, newest first. */
    @GetMapping("/change-requests")
    @PreAuthorize(READ)
    public ApiResponse<List<WebsiteService.ChangeRequestView>> changeRequests() {
        return ApiResponse.ok(websiteService.changeRequests());
    }

    /** Request a website edit → recorded for the Studio team (§8). */
    @PostMapping("/change-requests")
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<WebsiteService.ChangeRequestView>> requestChange(
            @Valid @RequestBody WebsiteChangeRequestBody body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(websiteService.requestChange(body.area(), body.details())));
    }

    /** Connect a custom domain (PRO; gating lands with Billing §7). */
    @PostMapping("/domain")
    @PreAuthorize(WRITE)
    public ApiResponse<WebsiteService.Site> connectDomain(@Valid @RequestBody ConnectDomainBody body) {
        return ApiResponse.ok(websiteService.connectDomain(body.domain()));
    }
}
