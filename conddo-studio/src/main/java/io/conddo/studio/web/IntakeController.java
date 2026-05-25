package io.conddo.studio.web;

import io.conddo.studio.common.ApiResponse;
import io.conddo.studio.domain.Job;
import io.conddo.studio.jobs.JobService;
import io.conddo.studio.web.dto.IntakeJobRequest;
import io.conddo.studio.web.dto.IntakeJobResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service job intake (SERVICE_TOPOLOGY.md §4) — how the platform
 * (conddo-api) places a job in the Studio queue when an owner requests work.
 * Authenticated by the {@code X-Studio-Service-Token} filter (ROLE_SERVICE), not
 * a staff JWT; see {@code StudioSecurityConfig}. Returns the job handle so the
 * platform can link + track it.
 */
@RestController
@RequestMapping("/api/jobs/intake")
public class IntakeController {

    private final JobService jobService;

    public IntakeController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<IntakeJobResponse>> intake(@Valid @RequestBody IntakeJobRequest request) {
        Job job = jobService.create(request.jobTypeId(), request.tenantId(), request.title(), request.brief());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(IntakeJobResponse.from(job)));
    }
}
