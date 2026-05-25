package io.conddo.studio.web;

import io.conddo.studio.auth.StudioPrincipal;
import io.conddo.studio.common.ApiResponse;
import io.conddo.studio.jobs.JobService;
import io.conddo.studio.web.dto.JobCard;
import io.conddo.studio.web.dto.JobDetailResponse;
import io.conddo.studio.web.dto.QaApproveRequest;
import io.conddo.studio.web.dto.QaReturnRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** QA queue + review (Infrastructure §13.2). Reviewers, team leads, and admins only. */
@RestController
@RequestMapping("/api/jobs/qa")
@PreAuthorize("hasAnyRole('QA_REVIEWER','TEAM_LEAD','ADMIN')")
public class QaController {

    private final JobService jobService;

    public QaController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping("/queue")
    public ApiResponse<List<JobCard>> queue() {
        return ApiResponse.ok(jobService.qaQueue().stream().map(JobCard::from).toList());
    }

    @PostMapping("/{id}/start")
    public ApiResponse<JobDetailResponse> start(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        jobService.qaStart(id, StudioPrincipal.staffId(jwt));
        return ApiResponse.ok(JobDetailResponse.from(jobService.detail(id)));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<JobDetailResponse> approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                                  @RequestBody(required = false) QaApproveRequest request) {
        jobService.qaApprove(id, StudioPrincipal.staffId(jwt),
                request == null ? null : request.checklist(),
                request == null ? null : request.positiveNotes());
        return ApiResponse.ok(JobDetailResponse.from(jobService.detail(id)));
    }

    @PostMapping("/{id}/return")
    public ApiResponse<JobDetailResponse> returnForRevision(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                                            @Valid @RequestBody QaReturnRequest request) {
        jobService.qaReturn(id, StudioPrincipal.staffId(jwt), request.checklist(), request.feedback());
        return ApiResponse.ok(JobDetailResponse.from(jobService.detail(id)));
    }
}
