package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.MarketingCampaign;
import io.conddo.core.domain.MarketingConnection;
import io.conddo.core.domain.MarketingLead;
import io.conddo.core.domain.MarketingPost;
import io.conddo.core.repository.MarketingCampaignRepository;
import io.conddo.core.repository.MarketingConnectionRepository;
import io.conddo.core.repository.MarketingLeadRepository;
import io.conddo.core.repository.MarketingPostRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Marketing posts + campaigns (§11.8). Tenant-scoped (RLS). Social publishing
 * and bulk email/SMS delivery are deferred (need the social OAuth and
 * Notifications-bulk integrations); these methods manage the schedule and stats.
 */
@Service
public class MarketingService {

    private static final Set<String> CAMPAIGN_TYPES = Set.of("email", "sms");

    private static final List<String> FUNNEL_STAGES = List.of("new", "contacted", "interested", "converted");

    private final MarketingPostRepository postRepository;
    private final MarketingCampaignRepository campaignRepository;
    private final MarketingLeadRepository leadRepository;
    private final MarketingConnectionRepository connectionRepository;
    private final TenantSession tenantSession;
    private final Clock clock;

    public MarketingService(MarketingPostRepository postRepository,
                            MarketingCampaignRepository campaignRepository,
                            MarketingLeadRepository leadRepository,
                            MarketingConnectionRepository connectionRepository,
                            TenantSession tenantSession, Clock clock) {
        this.postRepository = postRepository;
        this.campaignRepository = campaignRepository;
        this.leadRepository = leadRepository;
        this.connectionRepository = connectionRepository;
        this.tenantSession = tenantSession;
        this.clock = clock;
    }

    // ----- posts --------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<MarketingPost> listPosts(LocalDate from, LocalDate to, String platform) {
        tenantSession.bind();
        List<MarketingPost> posts;
        if (from != null || to != null) {
            OffsetDateTime start = (from != null ? from : LocalDate.now(clock).minusYears(1))
                    .atStartOfDay().atOffset(ZoneOffset.UTC);
            OffsetDateTime end = (to != null ? to.plusDays(1) : LocalDate.now(clock).plusYears(1))
                    .atStartOfDay().atOffset(ZoneOffset.UTC);
            posts = postRepository.findByScheduledAtBetweenOrderByScheduledAt(start, end);
        } else {
            posts = postRepository.findAllByOrderByScheduledAtAsc();
        }
        if (platform != null && !platform.isBlank()) {
            posts = posts.stream().filter(p -> p.getPlatforms().contains(platform)).toList();
        }
        return posts;
    }

    @Transactional
    public MarketingPost createPost(String title, String content, List<String> platforms,
                                    List<String> mediaIds, OffsetDateTime scheduledAt) {
        tenantSession.bind();
        return postRepository.save(new MarketingPost(
                TenantContext.require(), title, content, platforms, mediaIds, scheduledAt));
    }

    @Transactional(readOnly = true)
    public MarketingPost getPost(UUID id) {
        tenantSession.bind();
        return requirePost(id);
    }

    @Transactional
    public MarketingPost updatePost(UUID id, String title, String content, List<String> platforms,
                                    List<String> mediaIds, OffsetDateTime scheduledAt) {
        tenantSession.bind();
        MarketingPost post = requirePost(id);
        if (title != null) {
            post.setTitle(title);
        }
        if (content != null) {
            post.setContent(content);
        }
        post.setPlatforms(platforms);
        post.setMediaIds(mediaIds);
        post.setScheduledAt(scheduledAt);
        return postRepository.save(post);
    }

    @Transactional
    public void deletePost(UUID id) {
        tenantSession.bind();
        postRepository.delete(requirePost(id));
    }

    @Transactional
    public MarketingPost publishPost(UUID id) {
        tenantSession.bind();
        MarketingPost post = requirePost(id);
        post.publish(OffsetDateTime.now(clock));
        return postRepository.save(post);
    }

    // ----- campaigns ----------------------------------------------------------

    @Transactional(readOnly = true)
    public List<MarketingCampaign> listCampaigns(String type, String status) {
        tenantSession.bind();
        return campaignRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(c -> type == null || type.isBlank() || type.equalsIgnoreCase(c.getType()))
                .filter(c -> status == null || status.isBlank() || status.equalsIgnoreCase(c.getStatus()))
                .toList();
    }

    @Transactional
    public MarketingCampaign createCampaign(String name, String type, String content,
                                            Integer audienceSize, OffsetDateTime scheduledAt) {
        tenantSession.bind();
        String resolvedType = type == null ? "" : type.trim().toLowerCase();
        if (!CAMPAIGN_TYPES.contains(resolvedType)) {
            throw new IllegalArgumentException("Invalid campaign type: " + type);
        }
        return campaignRepository.save(new MarketingCampaign(TenantContext.require(), name, resolvedType,
                content, audienceSize == null ? 0 : audienceSize, scheduledAt));
    }

    @Transactional(readOnly = true)
    public MarketingCampaign getCampaign(UUID id) {
        tenantSession.bind();
        return campaignRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Campaign not found"));
    }

    // ----- leads --------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<MarketingLead> listLeads(String stage) {
        tenantSession.bind();
        return (stage == null || stage.isBlank())
                ? leadRepository.findAllByOrderByCreatedAtDesc()
                : leadRepository.findByStageOrderByCreatedAtDesc(stage.trim().toLowerCase());
    }

    @Transactional
    public MarketingLead createLead(String name, String email, String phone, String source) {
        tenantSession.bind();
        return leadRepository.save(new MarketingLead(TenantContext.require(), name, email, phone, source));
    }

    @Transactional
    public MarketingLead updateLead(UUID id, String stage, String name, BigDecimal value, String notes) {
        tenantSession.bind();
        MarketingLead lead = leadRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Lead not found"));
        if (stage != null) {
            lead.moveToStage(stage.trim().toLowerCase());
        }
        if (name != null) {
            lead.setName(name);
        }
        if (value != null) {
            lead.setValue(value);
        }
        if (notes != null) {
            lead.setNotes(notes);
        }
        return leadRepository.save(lead);
    }

    /** Funnel counts per stage (in pipeline order) + conversion rate. */
    @Transactional(readOnly = true)
    public Funnel funnel() {
        tenantSession.bind();
        Map<String, Long> counts = new LinkedHashMap<>();
        FUNNEL_STAGES.forEach(s -> counts.put(s, 0L));
        long total = 0;
        long converted = 0;
        for (Object[] row : leadRepository.countByStage()) {
            String stage = (String) row[0];
            long count = ((Number) row[1]).longValue();
            counts.merge(stage, count, Long::sum);
            total += count;
            if ("converted".equalsIgnoreCase(stage)) {
                converted += count;
            }
        }
        List<FunnelStage> stages = counts.entrySet().stream()
                .map(e -> new FunnelStage(e.getKey(), e.getValue())).toList();
        double conversionRate = total == 0 ? 0.0 : round1(converted * 100.0 / total);
        return new Funnel(stages, conversionRate);
    }

    // ----- connections --------------------------------------------------------

    @Transactional(readOnly = true)
    public List<MarketingConnection> connections() {
        tenantSession.bind();
        return connectionRepository.findAllByOrderByPlatform();
    }

    /** Connects (or re-links) a social account for a platform; idempotent per platform. */
    @Transactional
    public MarketingConnection connect(String platform, String handle) {
        tenantSession.bind();
        String resolved = platform == null ? "" : platform.trim().toLowerCase();
        if (resolved.isBlank()) {
            throw new IllegalArgumentException("Platform is required");
        }
        MarketingConnection connection = connectionRepository.findByPlatform(resolved)
                .orElseGet(() -> new MarketingConnection(TenantContext.require(), resolved, handle));
        connection.setHandle(handle);
        return connectionRepository.save(connection);
    }

    @Transactional
    public void disconnect(UUID id) {
        tenantSession.bind();
        connectionRepository.delete(connectionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Connection not found")));
    }

    // ----- overview summary ---------------------------------------------------

    /** The five overview KPI cards (§11.8). Social/ad metrics are placeholders until those integrations land. */
    @Transactional(readOnly = true)
    public Summary summary() {
        tenantSession.bind();
        long totalLeads = leadRepository.count();
        int sent = 0;
        int opened = 0;
        for (MarketingCampaign campaign : campaignRepository.findAllByOrderByCreatedAtDesc()) {
            if ("email".equalsIgnoreCase(campaign.getType())) {
                sent += campaign.getSent();
                opened += campaign.getOpened();
            }
        }
        double openRate = sent == 0 ? 0.0 : round1(opened * 100.0 / sent);
        return new Summary(
                new Kpi(0L, "Connect an account", "neutral"),                       // social reach
                new Kpi(0.0, "—", "neutral"),                                        // post engagement
                new Kpi(totalLeads, totalLeads == 0 ? "No leads yet" : totalLeads + " total",
                        totalLeads > 0 ? "success" : "neutral"),                     // new leads
                new Kpi(openRate, "across email campaigns", "neutral"),              // email open rate
                new Kpi(0L, "—", "neutral"));                                        // ad spend
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private MarketingPost requirePost(UUID id) {
        return postRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Post not found"));
    }

    /** A funnel stage and its lead count. */
    public record FunnelStage(String stage, long count) {
    }

    /** The leads funnel: per-stage counts + the conversion rate (%). */
    public record Funnel(List<FunnelStage> stages, double conversionRate) {
    }

    /** A marketing KPI card: value, a human delta label, and a tone. */
    public record Kpi(Object value, String delta, String tone) {
    }

    /** The five marketing overview KPI cards. */
    public record Summary(Kpi socialReach, Kpi postEngagement, Kpi newLeads, Kpi emailOpenRate, Kpi adSpend) {
    }
}
