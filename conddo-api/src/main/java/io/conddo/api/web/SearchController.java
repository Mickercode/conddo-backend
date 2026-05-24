package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.SearchService;
import io.conddo.core.service.SearchService.Results;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Global topbar search (§11.12) across customers, orders, and bookings. Tenant
 * comes from the JWT (RLS); open to any staff role.
 */
@RestController
@RequestMapping("/api/v1/search")
@PreAuthorize("hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public ApiResponse<Results> search(@RequestParam(name = "q", required = false) String query) {
        return ApiResponse.ok(searchService.search(query));
    }
}
