package io.conddo.studio.portability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.studio.builder.SiteService;
import io.conddo.studio.common.NotFoundException;
import io.conddo.studio.domain.Job;
import io.conddo.studio.domain.JobActivity;
import io.conddo.studio.domain.QaReview;
import io.conddo.studio.repository.JobActivityRepository;
import io.conddo.studio.repository.JobRepository;
import io.conddo.studio.repository.QaReviewRepository;
import io.conddo.studio.repository.SiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a ZIP bundle of a job's state for offline / local work (§22). The
 * bundle layout:
 * <pre>
 * &lt;jobNumber&gt;.conddo-studio.zip
 * ├── manifest.json     ← {@link BundleManifest} + checksum
 * ├── brief.md          ← human-readable brief
 * ├── README.md         ← "what to do with this bundle"
 * ├── site.json         ← Site + Pages + Sections (when a builder Site exists)
 * ├── ai-suggestions.json
 * ├── qa-history.json
 * └── activity.json
 * </pre>
 * Asset re-bundling (Cloudinary download per asset) is deferred — V1 includes
 * the asset metadata in the manifest plus the public URL list in
 * {@code site.json}, and the README documents how to fetch them. A later slice
 * will add server-side Cloudinary fetch + inline up to
 * {@code STUDIO_EXPORT_ASSET_INLINE_MAX_BYTES}.
 */
@Service
public class JobExportService {

    private static final Logger log = LoggerFactory.getLogger(JobExportService.class);
    private static final String CHECKSUM_PREFIX = "sha256:";

    private final JobRepository jobs;
    private final JobActivityRepository activities;
    private final QaReviewRepository qaReviews;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<SiteService> siteServiceProvider;
    private final SiteRepository siteRepository;

    public JobExportService(JobRepository jobs, JobActivityRepository activities,
                            QaReviewRepository qaReviews, ObjectMapper objectMapper,
                            ObjectProvider<SiteService> siteServiceProvider,
                            SiteRepository siteRepository) {
        this.jobs = jobs;
        this.activities = activities;
        this.qaReviews = qaReviews;
        this.objectMapper = objectMapper;
        this.siteServiceProvider = siteServiceProvider;
        this.siteRepository = siteRepository;
    }

    @Transactional(readOnly = true)
    public ExportResult exportJob(UUID jobId, UUID actorStaffId, String actorName, String actorEmail) {
        Job job = jobs.findById(jobId)
                .orElseThrow(() -> new NotFoundException("Job not found: " + jobId));
        List<JobActivity> activityRows = activities.findByJobIdOrderByCreatedAtDesc(jobId);
        List<QaReview> qaRows = qaReviews.findByJobIdOrderByCreatedAtDesc(jobId);

        // Builder Site — optional. Probe via the repo first so we don't trigger
        // SiteService.get's NotFoundException, which would mark this @Transactional
        // read-only context rollback-only and crash the export with 500.
        Object siteJsonModel = null;
        int siteVersion = 0;
        String siteStatus = "NONE";
        boolean hasSite = siteRepository.findByJobId(jobId).isPresent();
        SiteService siteService = siteServiceProvider.getIfAvailable();
        if (hasSite && siteService != null) {
            SiteService.SiteView view = siteService.get(jobId, actorStaffId, "ADMIN");
            siteJsonModel = serializeSiteView(view);
            siteVersion = view.site().getVersion();
            siteStatus = view.site().getStatus();
        }

        try {
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("brief.md", briefMarkdown(job));
            files.put("README.md", readmeMarkdown(job));
            files.put("ai-suggestions.json", jsonBytes(job.getAiSuggestions() == null
                    ? Map.of() : job.getAiSuggestions()));
            files.put("qa-history.json", jsonBytes(qaRows));
            files.put("activity.json", jsonBytes(activityRows));
            if (siteJsonModel != null) {
                files.put("site.json", jsonBytes(siteJsonModel));
            }
            // Content-only deterministic checksum (sorted by filename) — survives
            // ZIP-format quirks like embedded timestamps that vary per export.
            String checksum = CHECKSUM_PREFIX + sha256OfFiles(files);

            // Second pass — same files plus manifest with the checksum populated.
            BundleManifest manifest = new BundleManifest(
                    BundleManifest.CURRENT_SCHEMA_VERSION,
                    OffsetDateTime.now(),
                    new BundleManifest.Actor(actorStaffId, actorName, actorEmail),
                    new BundleManifest.JobSummary(job.getId(), job.getJobNumber(), siteVersion,
                            job.getTitle(), job.getJobTypeId(), job.getStatus(),
                            job.getTenantId(), job.getBrief()),
                    new BundleManifest.SiteSummary(siteVersion, siteStatus),
                    job.getAssets() == null ? List.of()
                            : job.getAssets().stream().map(a -> new BundleManifest.AssetSummary(
                                    UUID.fromString(String.valueOf(a.get("id"))),
                                    String.valueOf(a.get("fileName")),
                                    a.get("sizeBytes") instanceof Number n ? n.longValue() : 0L,
                                    null)).toList(),
                    checksum);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                writeEntry(zip, "manifest.json", jsonBytes(manifest));
                writeAll(zip, files);
            }
            return new ExportResult(job.getJobNumber() + ".conddo-studio.zip",
                    out.toByteArray(), checksum);
        } catch (java.io.IOException ex) {
            throw new RuntimeException("Failed to build export bundle: " + ex.getMessage(), ex);
        }
    }

    /** Write the same set of files into the streaming output (for the HTTP body). */
    public void streamExport(ExportResult result, OutputStream sink) throws java.io.IOException {
        sink.write(result.bytes());
        sink.flush();
    }

    // ----- internals ----------------------------------------------------------

    private byte[] briefMarkdown(Job job) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Job Brief — ").append(job.getJobNumber()).append("\n\n");
        sb.append("**Title:** ").append(job.getTitle()).append("\n");
        sb.append("**Type:** ").append(job.getJobTypeId()).append("\n");
        sb.append("**Status:** ").append(job.getStatus()).append("\n\n");
        sb.append("## Brief fields\n\n");
        if (job.getBrief() == null || job.getBrief().isEmpty()) {
            sb.append("_(no brief)_\n");
        } else {
            job.getBrief().forEach((k, v) -> sb.append("- **").append(k).append(":** ").append(v).append("\n"));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] readmeMarkdown(Job job) {
        return ("# " + job.getJobNumber() + ".conddo-studio.zip\n\n"
                + "This bundle is a snapshot of the Conddo Studio job state for offline work.\n\n"
                + "## Files\n\n"
                + "- `manifest.json` — bundle metadata + integrity checksum. Don't edit.\n"
                + "- `brief.md` — job brief in Markdown.\n"
                + "- `site.json` — builder Site state (pages + sections + theme).\n"
                + "- `ai-suggestions.json` — section AI suggestions snapshot.\n"
                + "- `qa-history.json` — QA review history.\n"
                + "- `activity.json` — full job activity log.\n\n"
                + "## Importing back\n\n"
                + "POST this ZIP to `/api/jobs/{id}/import` with the same job id; the\n"
                + "server verifies the checksum + job id + version and applies the diff.\n\n"
                + "## Assets\n\n"
                + "Asset files live on Cloudinary — re-download from the URLs in\n"
                + "`site.json` or the original /api/jobs/{id}/assets endpoint. A\n"
                + "future slice will inline small assets directly into the bundle.\n").getBytes(StandardCharsets.UTF_8);
    }

    private Object serializeSiteView(SiteService.SiteView view) {
        // Reuse SiteResponse's nested shape by going through the existing DTO.
        return io.conddo.studio.web.dto.SiteResponse.from(view);
    }

    private byte[] jsonBytes(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        } catch (Exception ex) {
            log.error("Could not serialise bundle entry: {}", ex.getMessage());
            return new byte[0];
        }
    }

    private static void writeAll(ZipOutputStream zip, Map<String, byte[]> files) throws java.io.IOException {
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            writeEntry(zip, e.getKey(), e.getValue());
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] body) throws java.io.IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(body);
        zip.closeEntry();
    }

    /** Deterministic content-only checksum over every non-manifest file (sorted). */
    static String sha256OfFiles(Map<String, byte[]> files) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            files.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> {
                        md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                        md.update((byte) 0);
                        md.update(e.getValue());
                    });
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new RuntimeException(ex);
        }
    }

    /** Output of {@link #exportJob}: a filename, the ZIP bytes, and the integrity checksum. */
    public record ExportResult(String filename, byte[] bytes, String checksum) {
    }
}
