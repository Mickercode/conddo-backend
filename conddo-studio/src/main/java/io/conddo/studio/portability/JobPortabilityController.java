package io.conddo.studio.portability;

import io.conddo.studio.auth.StudioPrincipal;
import io.conddo.studio.common.ApiResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Studio Job Export / Import (§22). Ops staff use these to take a job off the
 * cloud, work on it locally, and push their changes back. Authorization for
 * write back lives in the lifecycle services already (assignee-or-elevated);
 * the export side here is open to the same set.
 */
@RestController
@RequestMapping("/api/jobs/{jobId}")
public class JobPortabilityController {

    private final JobExportService exportService;
    private final JobImportService importService;

    public JobPortabilityController(JobExportService exportService, JobImportService importService) {
        this.exportService = exportService;
        this.importService = importService;
    }

    @GetMapping("/export")
    public ResponseEntity<Resource> export(@PathVariable UUID jobId,
                                           @AuthenticationPrincipal Jwt jwt) {
        JobExportService.ExportResult result = exportService.exportJob(jobId,
                StudioPrincipal.staffId(jwt),
                jwt.getClaimAsString("name"),
                jwt.getClaimAsString("email"));
        return ResponseEntity.ok()
                .contentType(new MediaType("application", "zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.filename() + "\"")
                .header("X-Bundle-Checksum", result.checksum())
                .body(new ByteArrayResource(result.bytes()));
    }

    @PostMapping("/import")
    public ApiResponse<JobImportService.ImportResult> importJob(@PathVariable UUID jobId,
                                                                @RequestParam("file") MultipartFile file,
                                                                @AuthenticationPrincipal Jwt jwt) throws java.io.IOException {
        // Caller auth: just authenticated for now — finer guard lives in the import service
        // when site replacement lands. The bundle's checksum + job-id check is the primary defence.
        StudioPrincipal.staffId(jwt);   // ensure principal is present
        try (java.io.InputStream in = file.getInputStream()) {
            return ApiResponse.ok(importService.importJob(jobId, in));
        }
    }
}
