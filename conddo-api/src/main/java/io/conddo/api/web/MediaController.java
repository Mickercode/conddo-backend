package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.storage.StorageException;
import io.conddo.core.service.MediaService;
import io.conddo.core.service.MediaService.MediaView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
import java.util.UUID;

/**
 * Tenant media library (§11.12): upload, list, fetch, delete. Files go to
 * S3-compatible object storage; responses carry a short-lived presigned {@code url}
 * the browser can load directly (the bucket stays private). The tenant comes from
 * the JWT and is enforced by RLS. Reads are open to any staff role; uploads and
 * deletes default to {@code TENANT_ADMIN}.
 */
@RestController
@RequestMapping("/api/v1/media")
public class MediaController {

    private static final String READ = "hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')";
    private static final String WRITE = "hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')";

    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    /**
     * Upload a file (multipart). {@code purpose} (logo|website|product|…) is
     * optional; {@code kind} is accepted as an alias for back-compat.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<MediaView>> upload(@RequestParam("file") MultipartFile file,
                                                         @RequestParam(required = false) String purpose,
                                                         @RequestParam(required = false) String kind) {
        try {
            MediaView view = mediaService.upload(file.getOriginalFilename(), file.getContentType(),
                    file.getSize(), file.getInputStream(), purpose != null ? purpose : kind);
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(view));
        } catch (IOException ex) {
            throw new StorageException("Could not read the uploaded file", ex);
        }
    }

    @GetMapping
    @PreAuthorize(READ)
    public ApiResponse<List<MediaView>> list(
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<MediaView> result = mediaService.list(kind, PageRequest.of(page, size));
        return ApiResponse.ok(result.getContent(),
                ApiResponse.Meta.page(result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    public ApiResponse<MediaView> get(@PathVariable UUID id) {
        return ApiResponse.ok(mediaService.get(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        mediaService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
