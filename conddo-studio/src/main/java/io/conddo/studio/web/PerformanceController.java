package io.conddo.studio.web;

import io.conddo.studio.auth.StudioPrincipal;
import io.conddo.studio.common.ApiResponse;
import io.conddo.studio.jobs.JobService;
import io.conddo.studio.web.dto.PerformanceDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Staff performance (Infrastructure §13.2): self for anyone, any staff for team leads/admins. */
@RestController
@RequestMapping("/api/jobs/performance")
public class PerformanceController {

    private final JobService jobService;

    public PerformanceController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping("/me")
    public ApiResponse<PerformanceDto> me(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(PerformanceDto.from(jobService.performance(StudioPrincipal.staffId(jwt))));
    }

    @GetMapping("/{staffId}")
    @PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN')")
    public ApiResponse<PerformanceDto> forStaff(@PathVariable UUID staffId) {
        return ApiResponse.ok(PerformanceDto.from(jobService.performance(staffId)));
    }
}
