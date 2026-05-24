package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.AnalyticsService;
import io.conddo.core.service.AnalyticsService.CustomersAnalytics;
import io.conddo.core.service.AnalyticsService.Overview;
import io.conddo.core.service.AnalyticsService.SeriesPoint;
import io.conddo.core.service.AnalyticsService.TopEntry;
import io.conddo.core.service.AnalyticsService.Traffic;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Business analytics (§11.9) — read-only aggregation over the tenant's data.
 * Tenant comes from the JWT (RLS). {@code range} accepts forms like {@code 7d},
 * {@code 4w}, {@code 6m} (default 30 days); {@code granularity} is day/week/month.
 * CSV/PDF export is deferred to a dedicated reporting pass.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@PreAuthorize("hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/overview")
    public ApiResponse<Overview> overview(@RequestParam(required = false) String range) {
        return ApiResponse.ok(analyticsService.overview(range));
    }

    @GetMapping("/revenue")
    public ApiResponse<List<SeriesPoint>> revenue(@RequestParam(required = false) String range,
                                                  @RequestParam(required = false) String granularity) {
        return ApiResponse.ok(analyticsService.revenueSeries(range, granularity));
    }

    @GetMapping("/orders")
    public ApiResponse<List<SeriesPoint>> orders(@RequestParam(required = false) String range,
                                                 @RequestParam(required = false) String granularity) {
        return ApiResponse.ok(analyticsService.ordersSeries(range, granularity));
    }

    @GetMapping("/customers")
    public ApiResponse<CustomersAnalytics> customers(@RequestParam(required = false) String range,
                                                     @RequestParam(required = false) String granularity) {
        return ApiResponse.ok(analyticsService.customers(range, granularity));
    }

    @GetMapping("/top")
    public ApiResponse<List<TopEntry>> top(@RequestParam(required = false, defaultValue = "services") String metric) {
        return ApiResponse.ok(analyticsService.top(metric));
    }

    @GetMapping("/traffic")
    public ApiResponse<Traffic> traffic(@RequestParam(required = false) String range) {
        return ApiResponse.ok(analyticsService.traffic(range));
    }
}
