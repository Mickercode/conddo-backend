package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.WebsiteChangeRequest;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.repository.WebsiteChangeRequestRepository;
import io.conddo.core.studio.StudioJobGateway;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import io.conddo.core.vertical.VerticalConfig;
import io.conddo.core.vertical.VerticalConfigRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Website module (§11.2). The tenant side is read + request-changes: the
 * site itself is built and published in Conddo Studio (§8). Publish state lives
 * on the tenant row; owner edit requests are recorded locally (the Studio job
 * hand-off is deferred). Section content and traffic analytics are read-only
 * snapshots — until Studio/web-analytics feed them, sections fall back to the
 * vertical's default layout and analytics report zero.
 */
@Service
public class WebsiteService {

    private final TenantRepository tenantRepository;
    private final WebsiteChangeRequestRepository changeRequestRepository;
    private final VerticalConfigRegistry verticalConfig;
    private final TenantSession tenantSession;
    private final Optional<StudioJobGateway> studioGateway;
    private final String baseDomain;

    public WebsiteService(TenantRepository tenantRepository,
                          WebsiteChangeRequestRepository changeRequestRepository,
                          VerticalConfigRegistry verticalConfig, TenantSession tenantSession,
                          Optional<StudioJobGateway> studioGateway,
                          @Value("${conddo.base-domain:conddo.io}") String baseDomain) {
        this.tenantRepository = tenantRepository;
        this.changeRequestRepository = changeRequestRepository;
        this.verticalConfig = verticalConfig;
        this.tenantSession = tenantSession;
        this.studioGateway = studioGateway;
        this.baseDomain = baseDomain.trim().toLowerCase();
    }

    /** Site config: {@code {subdomain, customDomain, status, publishedAt}}. */
    @Transactional(readOnly = true)
    public Site site() {
        Tenant tenant = requireTenant();
        return new Site(tenant.getSlug(), tenant.getCustomDomain(),
                tenant.getWebsiteStatus(), tenant.getWebsitePublishedAt());
    }

    /** Status widget (also reused by the dashboard): {@code {state, domain, visitsToday, enquiries}}. */
    @Transactional(readOnly = true)
    public LiveStatus status() {
        Tenant tenant = requireTenant();
        return new LiveStatus(stateOf(tenant), domainOf(tenant), 0, 0);
    }

    /** Configured sections (read-only). Falls back to the vertical's default layout until Studio feeds real content. */
    @Transactional(readOnly = true)
    public List<Section> sections() {
        Tenant tenant = requireTenant();
        VerticalConfig config = verticalConfig.find(tenant.getVerticalId());
        if (config == null) {
            config = verticalConfig.require("general");
        }
        return config.websiteSections().stream()
                .map(type -> new Section(type, humanize(type), false))
                .toList();
    }

    /** Visits/enquiries/top-pages over a range. Zero until web-analytics is wired (§11.2). */
    @Transactional(readOnly = true)
    public Analytics analytics(String range) {
        requireTenant();
        return new Analytics(range == null || range.isBlank() ? "30d" : range, 0, 0, List.of());
    }

    @Transactional(readOnly = true)
    public List<ChangeRequestView> changeRequests() {
        tenantSession.bind();
        return changeRequestRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(ChangeRequestView::from).toList();
    }

    /**
     * Records an owner edit request and hands it to Conddo Studio as a job
     * (SERVICE_TOPOLOGY.md §4). If Studio isn't configured, or the hand-off can't
     * be placed, the request stays {@code PENDING} for manual pickup — the owner's
     * request never fails on a Studio outage.
     */
    @Transactional
    public ChangeRequestView requestChange(String area, String details) {
        if (details == null || details.isBlank()) {
            throw new IllegalArgumentException("Describe the change you'd like");
        }
        tenantSession.bind();
        Tenant tenant = requireTenant();
        UUID tenantId = TenantContext.require();
        WebsiteChangeRequest request = changeRequestRepository.save(
                new WebsiteChangeRequest(tenantId, area, details.trim()));
        handOffToStudio(tenant, tenantId, request);
        return ChangeRequestView.from(request);
    }

    /** Creates the Studio job for a change request and links the job id back onto it. */
    private void handOffToStudio(Tenant tenant, UUID tenantId, WebsiteChangeRequest request) {
        StudioJobGateway gateway = studioGateway.orElse(null);
        if (gateway == null) {
            return;
        }
        // A site that's never been built needs a BUILD; an existing one needs a REVISION.
        boolean unbuilt = "NOT_STARTED".equalsIgnoreCase(tenant.getWebsiteStatus());
        String jobType = unbuilt ? "WEBSITE_BUILD" : "WEBSITE_REVISION";
        String title = (unbuilt ? "Website build — " : "Website edit — ") + tenant.getName();

        Map<String, Object> brief = new LinkedHashMap<>();
        brief.put("source", "website-change-request");
        if (request.getId() != null) {
            brief.put("changeRequestId", request.getId().toString());
        }
        if (request.getArea() != null && !request.getArea().isBlank()) {
            brief.put("area", request.getArea());
        }
        brief.put("details", request.getDetails());
        brief.put("businessName", tenant.getName());

        gateway.createJob(tenantId, jobType, title, brief).ifPresent(ref -> {
            request.markSubmittedToStudio(ref.jobId());
            changeRequestRepository.save(request);
        });
    }

    /**
     * Connects a custom domain (§11.2). PRO-gating is enforced once Billing (§7)
     * is wired; for now any tenant may set it. DNS verification is the operator's
     * step — the domain is recorded immediately and serves once DNS points here.
     */
    @Transactional
    public Site connectDomain(String domain) {
        Tenant tenant = requireTenant();
        tenant.setCustomDomain(normalizeDomain(domain));
        tenantRepository.save(tenant);
        return new Site(tenant.getSlug(), tenant.getCustomDomain(),
                tenant.getWebsiteStatus(), tenant.getWebsitePublishedAt());
    }

    // ----- internals ----------------------------------------------------------

    private Tenant requireTenant() {
        return tenantRepository.findById(TenantContext.require())
                .orElseThrow(() -> new NotFoundException("Tenant not found"));
    }

    private static String stateOf(Tenant tenant) {
        return "LIVE".equalsIgnoreCase(tenant.getWebsiteStatus()) ? "live" : "in_progress";
    }

    private String domainOf(Tenant tenant) {
        return tenant.getCustomDomain() != null ? tenant.getCustomDomain()
                : tenant.getSlug() + "." + baseDomain;
    }

    /** Validates and normalises a bare hostname (no scheme, no path). */
    private static String normalizeDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("Domain is required");
        }
        String d = domain.trim().toLowerCase();
        if (d.contains("://") || d.contains("/") || d.contains(" ") || !d.contains(".")) {
            throw new IllegalArgumentException("Enter a bare domain, e.g. shop.example.com");
        }
        return d;
    }

    private static String humanize(String type) {
        if (type == null || type.isEmpty()) {
            return type;
        }
        String spaced = type.replace('-', ' ').replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    // ----- response records ----------------------------------------------------

    /** {@code GET /website}. {@code publishedAt} is null until the site goes live. */
    public record Site(String subdomain, String customDomain, String status, OffsetDateTime publishedAt) {
    }

    /** {@code GET /website/status}. {@code state} ∈ live | in_progress. */
    public record LiveStatus(String state, String domain, long visitsToday, long enquiries) {
    }

    /** A configured website section. {@code configured} is false until Studio supplies real content. */
    public record Section(String type, String label, boolean configured) {
    }

    /** {@code GET /website/analytics}. Counts are zero until traffic tracking is in place. */
    public record Analytics(String range, long visits, long enquiries, List<TopPage> topPages) {
    }

    /** A top-page row (path + views) — reserved for when web-analytics lands. */
    public record TopPage(String path, long views) {
    }

    /** A recorded edit request (§11.2). */
    public record ChangeRequestView(java.util.UUID id, String area, String details, String status,
                                    OffsetDateTime createdAt) {
        static ChangeRequestView from(WebsiteChangeRequest r) {
            return new ChangeRequestView(r.getId(), r.getArea(), r.getDetails(), r.getStatus(), r.getCreatedAt());
        }
    }
}
