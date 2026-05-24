package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.DashboardService;
import io.conddo.core.service.DashboardService.Checklist;
import io.conddo.core.service.DashboardService.Summary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The home dashboard (§11.1): KPI summary and the setup checklist. Tenant-scoped
 * via the JWT (RLS). The recent-orders, today's-bookings, and website-status
 * widgets reuse the orders/bookings/website endpoints, so they live there.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private static final String READ = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";
    private static final String WRITE = "hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')";

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    @PreAuthorize(READ)
    public ApiResponse<Summary> summary() {
        return ApiResponse.ok(dashboardService.summary());
    }

    @GetMapping("/setup-checklist")
    @PreAuthorize(READ)
    public ApiResponse<Checklist> checklist() {
        return ApiResponse.ok(dashboardService.setupChecklist());
    }

    @PostMapping("/setup-checklist/{key}/dismiss")
    @PreAuthorize(WRITE)
    public ApiResponse<Checklist> dismiss(@PathVariable String key) {
        dashboardService.dismissStep(key);
        return ApiResponse.ok(dashboardService.setupChecklist());
    }
}
