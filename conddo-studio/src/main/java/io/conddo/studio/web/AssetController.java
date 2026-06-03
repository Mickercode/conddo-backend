package io.conddo.studio.web;

import io.conddo.studio.assets.AssetService;
import io.conddo.studio.auth.StudioPrincipal;
import io.conddo.studio.common.ApiResponse;
import io.conddo.studio.storage.StorageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Job-asset uploads (§9). Any authenticated staff member can upload/list/delete
 * assets on a job — frontend gates per role. Bytes go to Cloudinary; the index
 * lives on the job's {@code assets} JSONB array.
 */
@RestController
@RequestMapping("/api/jobs/{jobId}/assets")
public class AssetController {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> upload(@PathVariable UUID jobId,
                                                                   @RequestParam("file") MultipartFile file,
                                                                   @AuthenticationPrincipal Jwt jwt) {
        try {
            Map<String, Object> asset = assetService.upload(jobId, file.getOriginalFilename(),
                    file.getContentType(), file.getSize(), file.getInputStream(),
                    jwt == null ? null : StudioPrincipal.staffId(jwt));
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(asset));
        } catch (IOException ex) {
            throw new StorageException("Could not read the uploaded file", ex);
        }
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@PathVariable UUID jobId) {
        return ApiResponse.ok(assetService.list(jobId));
    }

    @DeleteMapping("/{assetId}")
    public ResponseEntity<Void> delete(@PathVariable UUID jobId, @PathVariable UUID assetId) {
        assetService.delete(jobId, assetId);
        return ResponseEntity.noContent().build();
    }
}
