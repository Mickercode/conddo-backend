package io.conddo.studio.assets;

import io.conddo.studio.common.NotFoundException;
import io.conddo.studio.domain.Job;
import io.conddo.studio.repository.JobRepository;
import io.conddo.studio.storage.ObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Job-asset uploads (§9): designers/devs attach deliverables (logos, designs,
 * mockups) to a job; QA + the developer team see them on the job detail. Bytes
 * go to {@link ObjectStorage} (Cloudinary); the index lives on
 * {@code studio.jobs.assets} (JSONB array already provided by V1). Each entry:
 *
 * <pre>{@code
 * { id, fileName, mimeType, sizeBytes, publicId, url, uploadedAt, uploadedBy }
 * }</pre>
 *
 * <p>Fail-safe by §20 AI rules' family: the bucket bytes live in Cloudinary; if
 * that's down, upload returns a 502 cleanly. The job row is the source of truth
 * for what assets exist.
 */
@Service
public class AssetService {

    private static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;   // 10 MB
    static final String FIELD_ID = "id";
    static final String FIELD_PUBLIC_ID = "publicId";

    private final JobRepository jobRepository;
    private final ObjectStorage storage;
    private final long maxBytes;

    public AssetService(JobRepository jobRepository, ObjectStorage storage,
                        @Value("${studio.assets.max-bytes:" + DEFAULT_MAX_BYTES + "}") long maxBytes) {
        this.jobRepository = jobRepository;
        this.storage = storage;
        this.maxBytes = maxBytes;
    }

    @Transactional
    public Map<String, Object> upload(UUID jobId, String originalName, String contentType,
                                      long size, InputStream data, UUID uploadedBy) {
        Job job = require(jobId);
        validate(contentType, size);

        UUID assetId = UUID.randomUUID();
        String key = "studio/jobs/" + jobId + "/" + assetId;
        ObjectStorage.Stored stored = storage.put(key, contentType, size, data);

        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put(FIELD_ID, assetId.toString());
        asset.put("fileName", originalName == null ? "file" : originalName);
        asset.put("mimeType", contentType);
        asset.put("sizeBytes", size);
        asset.put(FIELD_PUBLIC_ID, stored.id());
        asset.put("url", stored.url());
        asset.put("uploadedAt", OffsetDateTime.now().toString());
        if (uploadedBy != null) {
            asset.put("uploadedBy", uploadedBy.toString());
        }

        appendAsset(job, asset);
        jobRepository.save(job);
        return asset;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID jobId) {
        return require(jobId).getAssets();
    }

    @Transactional
    public void delete(UUID jobId, UUID assetId) {
        Job job = require(jobId);
        List<Map<String, Object>> assets = job.getAssets() == null
                ? List.of() : new ArrayList<>(job.getAssets());

        Map<String, Object> match = assets.stream()
                .filter(a -> assetId.toString().equals(a.get(FIELD_ID)))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Asset " + assetId + " not on job"));

        storage.delete((String) match.get(FIELD_PUBLIC_ID));
        assets.removeIf(a -> assetId.toString().equals(a.get(FIELD_ID)));
        replaceAssets(job, assets);
        jobRepository.save(job);
    }

    // ----- internals ----------------------------------------------------------

    private void appendAsset(Job job, Map<String, Object> asset) {
        List<Map<String, Object>> current = job.getAssets() == null
                ? new ArrayList<>() : new ArrayList<>(job.getAssets());
        current.add(asset);
        replaceAssets(job, current);
    }

    private void replaceAssets(Job job, List<Map<String, Object>> assets) {
        // Job.assets is package-private to the entity; mutate via a tiny method we add there.
        job.setAssets(Objects.requireNonNullElseGet(assets, ArrayList::new));
    }

    private Job require(UUID id) {
        return jobRepository.findById(id).orElseThrow(() -> new NotFoundException("Job not found"));
    }

    private void validate(String contentType, long size) {
        if (size <= 0) {
            throw new IllegalArgumentException("File is empty");
        }
        if (size > maxBytes) {
            throw new IllegalArgumentException("File exceeds the " + (maxBytes / (1024 * 1024)) + "MB limit");
        }
        if (contentType == null || !(contentType.startsWith("image/") || contentType.equals("application/pdf"))) {
            throw new IllegalArgumentException("Unsupported file type: " + contentType
                    + " (allowed: images, PDF)");
        }
    }
}
