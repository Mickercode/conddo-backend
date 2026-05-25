package io.conddo.studio.web;

import io.conddo.studio.common.ApiResponse;
import io.conddo.studio.jobs.JobService;
import io.conddo.studio.staff.StaffService;
import io.conddo.studio.web.dto.AdminDashboardResponse;
import io.conddo.studio.web.dto.CreateJobRequest;
import io.conddo.studio.web.dto.CreateStaffRequest;
import io.conddo.studio.web.dto.EscalateRequest;
import io.conddo.studio.web.dto.ExtendSlaRequest;
import io.conddo.studio.web.dto.JobDetailResponse;
import io.conddo.studio.web.dto.ReassignRequest;
import io.conddo.studio.web.dto.StaffDto;
import io.conddo.studio.web.dto.UpdateStaffRequest;
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

/** Team-lead / admin operations (Infrastructure §13.2): board overview, staff, reassign/SLA/escalate. */
@RestController
@RequestMapping("/api/jobs/admin")
@PreAuthorize("hasAnyRole('TEAM_LEAD','ADMIN')")
public class AdminController {

    private final JobService jobService;
    private final StaffService staffService;

    public AdminController(JobService jobService, StaffService staffService) {
        this.jobService = jobService;
        this.staffService = staffService;
    }

    @GetMapping("/dashboard")
    public ApiResponse<AdminDashboardResponse> dashboard() {
        return ApiResponse.ok(AdminDashboardResponse.from(jobService.allJobs()));
    }

    @GetMapping("/staff")
    public ApiResponse<List<StaffDto>> staff() {
        return ApiResponse.ok(staffService.list().stream().map(StaffDto::from).toList());
    }

    @PostMapping("/staff")
    public ResponseEntity<ApiResponse<StaffDto>> createStaff(@Valid @RequestBody CreateStaffRequest request) {
        StaffDto body = StaffDto.from(staffService.create(
                request.email(), request.fullName(), request.role(), request.skills(), request.password()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    @PatchMapping("/staff/{id}")
    public ApiResponse<StaffDto> updateStaff(@PathVariable UUID id, @RequestBody UpdateStaffRequest request) {
        return ApiResponse.ok(StaffDto.from(staffService.update(id, request.role(), request.active())));
    }

    @PostMapping("/jobs")
    public ResponseEntity<ApiResponse<JobDetailResponse>> createJob(@Valid @RequestBody CreateJobRequest request) {
        UUID jobId = jobService.create(request.jobTypeId(), request.tenantId(), request.title(), request.brief()).getId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(JobDetailResponse.from(jobService.detail(jobId))));
    }

    @PatchMapping("/{id}/reassign")
    public ApiResponse<JobDetailResponse> reassign(@PathVariable UUID id, @Valid @RequestBody ReassignRequest request) {
        jobService.reassign(id, request.staffId());
        return ApiResponse.ok(JobDetailResponse.from(jobService.detail(id)));
    }

    @PatchMapping("/{id}/extend-sla")
    public ApiResponse<JobDetailResponse> extendSla(@PathVariable UUID id, @Valid @RequestBody ExtendSlaRequest request) {
        jobService.extendSla(id, request.hours(), request.reason());
        return ApiResponse.ok(JobDetailResponse.from(jobService.detail(id)));
    }

    @PatchMapping("/{id}/escalate")
    public ApiResponse<JobDetailResponse> escalate(@PathVariable UUID id,
                                                   @RequestBody(required = false) EscalateRequest request) {
        jobService.escalate(id, request == null ? null : request.reason());
        return ApiResponse.ok(JobDetailResponse.from(jobService.detail(id)));
    }
}
