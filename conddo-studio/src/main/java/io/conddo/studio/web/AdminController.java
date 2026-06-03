package io.conddo.studio.web;

import io.conddo.studio.common.ApiResponse;
import io.conddo.studio.jobs.JobService;
import io.conddo.studio.jobs.JobTypeService;
import io.conddo.studio.staff.StaffService;
import io.conddo.studio.web.dto.AdminDashboardResponse;
import io.conddo.studio.web.dto.CreateJobRequest;
import io.conddo.studio.web.dto.CreateJobTypeRequest;
import io.conddo.studio.web.dto.CreateStaffRequest;
import io.conddo.studio.web.dto.EscalateRequest;
import io.conddo.studio.web.dto.ExtendSlaRequest;
import io.conddo.studio.web.dto.JobDetailResponse;
import io.conddo.studio.web.dto.JobTypeDto;
import io.conddo.studio.web.dto.ReassignRequest;
import io.conddo.studio.web.dto.StaffDto;
import io.conddo.studio.web.dto.UpdateJobTypeRequest;
import io.conddo.studio.web.dto.UpdateStaffRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final JobTypeService jobTypeService;

    public AdminController(JobService jobService, StaffService staffService,
                           JobTypeService jobTypeService) {
        this.jobService = jobService;
        this.staffService = staffService;
        this.jobTypeService = jobTypeService;
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

    // ----- job-type catalogue (Phase 8 §8 — SLA-settings) --------------------

    /** Production catalogue: visible to leads + admins so they can review the SLA matrix. */
    @GetMapping("/job-types")
    public ApiResponse<List<JobTypeDto>> listJobTypes() {
        return ApiResponse.ok(jobTypeService.list().stream().map(JobTypeDto::from).toList());
    }

    /** Add a new job type (e.g. SOCIAL_MEDIA). ADMIN-only — only the production manager curates the catalogue. */
    @PostMapping("/job-types")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<JobTypeDto>> createJobType(@Valid @RequestBody CreateJobTypeRequest request) {
        JobTypeDto body = JobTypeDto.from(jobTypeService.create(
                request.id(), request.displayName(), request.colour(),
                request.assignedToRoles(), request.slaHours(),
                request.qaRequired() != null && request.qaRequired(),
                request.qaChecklist()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    /** Tune an existing type — SLA hours, label, checklist, activation. PATCH semantics (null = no-op). */
    @PatchMapping("/job-types/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<JobTypeDto> updateJobType(@PathVariable String id,
                                                 @Valid @RequestBody UpdateJobTypeRequest request) {
        return ApiResponse.ok(JobTypeDto.from(jobTypeService.update(id,
                request.displayName(), request.colour(), request.assignedToRoles(),
                request.slaHours(), request.qaRequired(), request.qaChecklist(), request.active())));
    }

    /** Soft-disable — past jobs keep their reference; the type drops off the create-job dropdown. */
    @DeleteMapping("/job-types/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> disableJobType(@PathVariable String id) {
        jobTypeService.disable(id);
        return ResponseEntity.noContent().build();
    }
}
