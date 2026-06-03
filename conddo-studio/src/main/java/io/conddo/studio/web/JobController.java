package io.conddo.studio.web;

import io.conddo.studio.ai.AiAssistantService;
import io.conddo.studio.auth.StudioPrincipal;
import io.conddo.studio.common.ApiResponse;
import io.conddo.studio.jobs.JobService;
import io.conddo.studio.web.dto.AiSuggestRequest;
import io.conddo.studio.web.dto.JobCard;
import io.conddo.studio.web.dto.JobDetailResponse;
import io.conddo.studio.web.dto.PaletteRequest;
import io.conddo.studio.web.dto.RankImagesRequest;
import io.conddo.studio.web.dto.SubmitJobRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Staff-facing job queue + lifecycle (Infrastructure §13.2). Any authenticated
 * staff member; the service enforces ownership of start/submit.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping("/my-jobs")
    public ApiResponse<List<JobCard>> myJobs(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(jobService.myJobs(StudioPrincipal.staffId(jwt)).stream().map(JobCard::from).toList());
    }

    @GetMapping("/available")
    public ApiResponse<List<JobCard>> available(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(jobService.available(StudioPrincipal.staffId(jwt)).stream().map(JobCard::from).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<JobDetailResponse> detail(@PathVariable UUID id) {
        return ApiResponse.ok(JobDetailResponse.from(jobService.detail(id)));
    }

    @PostMapping("/{id}/claim")
    public ApiResponse<JobDetailResponse> claim(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        jobService.claim(id, StudioPrincipal.staffId(jwt));
        return ApiResponse.ok(JobDetailResponse.from(jobService.detail(id)));
    }

    @PatchMapping("/{id}/start")
    public ApiResponse<JobDetailResponse> start(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        jobService.start(id, StudioPrincipal.staffId(jwt));
        return ApiResponse.ok(JobDetailResponse.from(jobService.detail(id)));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<JobDetailResponse> submit(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                                 @RequestBody(required = false) SubmitJobRequest request) {
        String url = request == null ? null : request.studioUrl();
        String notes = request == null ? null : request.notes();
        jobService.submit(id, StudioPrincipal.staffId(jwt), url, notes);
        return ApiResponse.ok(JobDetailResponse.from(jobService.detail(id)));
    }

    // ----- AI assistant (§8) --------------------------------------------------

    /** Generate AI copy for a section and store it on the job. {@code available:false} if Claude is off. */
    @PostMapping("/{id}/ai-suggest")
    public ApiResponse<AiAssistantService.CopyResult> aiSuggest(@PathVariable UUID id,
                                                                @Valid @RequestBody AiSuggestRequest request) {
        return ApiResponse.ok(jobService.aiSuggest(id, request.section()));
    }

    /** Generate an accessible colour palette from a primary hex (no job required). */
    @PostMapping("/ai/palette")
    public ApiResponse<AiAssistantService.PaletteResult> palette(@Valid @RequestBody PaletteRequest request) {
        return ApiResponse.ok(jobService.aiPalette(request.primaryHex(), request.vertical()));
    }

    /** Rank candidate images for a section using Claude vision. */
    @PostMapping("/{id}/rank-images")
    public ApiResponse<AiAssistantService.RankResult> rankImages(@PathVariable UUID id,
                                                                 @Valid @RequestBody RankImagesRequest request) {
        return ApiResponse.ok(jobService.aiRankImages(id, request.imageUrls(), request.sectionType()));
    }
}
