package io.conddo.studio.platform;

import io.conddo.studio.common.ConflictException;
import io.conddo.studio.common.NotFoundException;
import io.conddo.studio.domain.Staff;
import io.conddo.studio.repository.StaffRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Studio Platform Admin — Site Registration
 * (SITE_REGISTRATION_ADMIN_SPEC §3-6). Lets ops register tenant_sites,
 * issue/rotate API keys, QA-approve, edit metadata, and read the audit
 * log — all from the Studio admin UI instead of raw SQL.
 *
 * <p>Tenant_sites lives in {@code public} schema (owned by conddo-api,
 * V25 platform migrations); the audit log lives in {@code studio}.
 * Both accessed by the {@code conddo_owner} role Studio runs as.
 */
@Service
public class PlatformSiteAdminService {

    private static final String KEY_PREFIX = "sk_live_";
    private static final int KEY_BODY_BYTES = 24;   // → 32 base64-url chars
    private static final Pattern CUSTOM_DOMAIN = Pattern.compile("^[a-z0-9-]+(\\.[a-z0-9-]+)+$");

    private final PlatformTenantSiteRepository siteRepository;
    private final PlatformTenantRepository tenantRepository;
    private final SiteAuditLogRepository auditRepository;
    private final StaffRepository staffRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public PlatformSiteAdminService(PlatformTenantSiteRepository siteRepository,
                                    PlatformTenantRepository tenantRepository,
                                    SiteAuditLogRepository auditRepository,
                                    StaffRepository staffRepository,
                                    PasswordEncoder passwordEncoder) {
        this.siteRepository = siteRepository;
        this.tenantRepository = tenantRepository;
        this.auditRepository = auditRepository;
        this.staffRepository = staffRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // ----- reads -------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<PlatformTenantSite> list() {
        return siteRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public PlatformTenantSite get(UUID siteId) {
        return require(siteId);
    }

    @Transactional(readOnly = true)
    public Optional<PlatformTenant> tenantOf(UUID tenantId) {
        return tenantRepository.findById(tenantId);
    }

    @Transactional(readOnly = true)
    public List<SiteAuditLog> auditLog(UUID siteId) {
        return auditRepository.findBySiteIdOrderByCreatedAtDesc(siteId);
    }

    @Transactional(readOnly = true)
    public Optional<Staff> staffById(UUID staffId) {
        return staffId == null ? Optional.empty() : staffRepository.findById(staffId);
    }

    // ----- register ----------------------------------------------------------

    /**
     * Spec §5.1 — ops registers a new site on a tenant's behalf. Generates
     * the first API key, persists the bcrypt hash + last4, writes a
     * {@code REGISTERED} audit row, and returns the plaintext key for the
     * controller to surface in the one-time response.
     */
    @Transactional
    public RegisterResult register(UUID byStaffId, UUID tenantId,
                                   String subdomain, String customDomain,
                                   String hostingProvider, String siteType,
                                   String submittedUrl) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if ((subdomain == null || subdomain.isBlank())
                && (customDomain == null || customDomain.isBlank())) {
            throw new IllegalArgumentException(
                    "At least one of subdomain or customDomain must be provided");
        }
        tenantRepository.findById(tenantId).orElseThrow(() ->
                new NotFoundException("Tenant not found: " + tenantId));
        if (siteRepository.findByTenantId(tenantId).isPresent()) {
            throw new ConflictException("TENANT_ALREADY_HAS_SITE",
                    "Tenant already has a registered site — use rotate-key or patch instead.");
        }
        String normSub = normaliseSubdomain(subdomain);
        String normDomain = normaliseCustomDomain(customDomain);
        if (normSub != null && siteRepository.findBySubdomain(normSub).isPresent()) {
            throw new ConflictException("SUBDOMAIN_TAKEN", "Subdomain '" + normSub + "' is taken.");
        }
        if (normDomain != null && siteRepository.findByCustomDomain(normDomain).isPresent()) {
            throw new ConflictException("CUSTOM_DOMAIN_TAKEN", "Custom domain '" + normDomain + "' is taken.");
        }

        String plaintext = generatePlaintextKey();
        String hash = passwordEncoder.encode(plaintext);
        String last4 = plaintext.substring(plaintext.length() - 4);

        PlatformTenantSite site = new PlatformTenantSite(
                UUID.randomUUID(), tenantId, normSub, normDomain,
                blankToNull(hostingProvider), blankToNull(siteType), blankToNull(submittedUrl),
                hash, last4, OffsetDateTime.now());
        try {
            site = siteRepository.saveAndFlush(site);
        } catch (DataIntegrityViolationException ex) {
            // Race with another registration on the same subdomain/domain.
            throw new ConflictException("SUBDOMAIN_OR_DOMAIN_TAKEN",
                    "Subdomain or custom domain is taken — refresh and try again.");
        }

        audit(site.getId(), SiteAuditLog.ACTION_REGISTERED, byStaffId,
                "Registered with key ending in " + last4);
        return new RegisterResult(site, plaintext);
    }

    // ----- patch metadata ---------------------------------------------------

    @Transactional
    public PlatformTenantSite patchMetadata(UUID byStaffId, UUID siteId,
                                            String subdomain, String customDomain,
                                            String hostingProvider, String siteType,
                                            String submittedUrl) {
        PlatformTenantSite site = require(siteId);
        final UUID thisSiteId = site.getId();   // captured by the uniqueness lambdas below
        Map<String, Object> diff = new LinkedHashMap<>();

        if (subdomain != null) {
            String norm = normaliseSubdomain(subdomain);
            if (!java.util.Objects.equals(norm, site.getSubdomain())) {
                if (norm != null && siteRepository.findBySubdomain(norm)
                        .filter(s -> !s.getId().equals(thisSiteId)).isPresent()) {
                    throw new ConflictException("SUBDOMAIN_TAKEN",
                            "Subdomain '" + norm + "' is taken.");
                }
                diff.put("subdomain", new String[]{site.getSubdomain(), norm});
                site.setSubdomain(norm);
            }
        }
        if (customDomain != null) {
            String norm = normaliseCustomDomain(customDomain);
            if (!java.util.Objects.equals(norm, site.getCustomDomain())) {
                if (norm != null && siteRepository.findByCustomDomain(norm)
                        .filter(s -> !s.getId().equals(thisSiteId)).isPresent()) {
                    throw new ConflictException("CUSTOM_DOMAIN_TAKEN",
                            "Custom domain '" + norm + "' is taken.");
                }
                diff.put("customDomain", new String[]{site.getCustomDomain(), norm});
                site.setCustomDomain(norm);
            }
        }
        if (hostingProvider != null && !hostingProvider.equals(site.getHostingProvider())) {
            diff.put("hostingProvider", new String[]{site.getHostingProvider(), blankToNull(hostingProvider)});
            site.setHostingProvider(blankToNull(hostingProvider));
        }
        if (siteType != null && !siteType.equals(site.getSiteType())) {
            diff.put("siteType", new String[]{site.getSiteType(), blankToNull(siteType)});
            site.setSiteType(blankToNull(siteType));
        }
        if (submittedUrl != null && !submittedUrl.equals(site.getSubmittedUrl())) {
            diff.put("submittedUrl", new String[]{site.getSubmittedUrl(), blankToNull(submittedUrl)});
            site.setSubmittedUrl(blankToNull(submittedUrl));
        }
        if (diff.isEmpty()) {
            return site;   // no-op; don't write an audit entry
        }
        try {
            site = siteRepository.saveAndFlush(site);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("SUBDOMAIN_OR_DOMAIN_TAKEN",
                    "Subdomain or custom domain is taken — refresh and try again.");
        }
        audit(site.getId(), SiteAuditLog.ACTION_METADATA_UPDATED, byStaffId,
                renderDiff(diff));
        return site;
    }

    // ----- key rotation -----------------------------------------------------

    @Transactional
    public RegisterResult rotateKey(UUID byStaffId, UUID siteId) {
        PlatformTenantSite site = require(siteId);
        String plaintext = generatePlaintextKey();
        String last4 = plaintext.substring(plaintext.length() - 4);
        site.rotateKey(passwordEncoder.encode(plaintext), last4);
        site = siteRepository.save(site);
        audit(site.getId(), SiteAuditLog.ACTION_KEY_ROTATED, byStaffId,
                "Rotated to key ending in " + last4);
        return new RegisterResult(site, plaintext);
    }

    // ----- QA / activation --------------------------------------------------

    @Transactional
    public PlatformTenantSite qaApprove(UUID byStaffId, UUID siteId, String note) {
        PlatformTenantSite site = require(siteId);
        site.approveQa(byStaffId, OffsetDateTime.now());
        site = siteRepository.save(site);
        audit(site.getId(), SiteAuditLog.ACTION_QA_APPROVED, byStaffId,
                note == null || note.isBlank() ? "QA approved" : note);
        return site;
    }

    @Transactional
    public PlatformTenantSite qaRevoke(UUID byStaffId, UUID siteId, String note) {
        PlatformTenantSite site = require(siteId);
        site.revokeQa();
        site = siteRepository.save(site);
        audit(site.getId(), SiteAuditLog.ACTION_QA_REVOKED, byStaffId,
                note == null || note.isBlank() ? "QA approval revoked" : note);
        return site;
    }

    @Transactional
    public PlatformTenantSite activate(UUID byStaffId, UUID siteId) {
        PlatformTenantSite site = require(siteId);
        if (site.isActive()) {
            return site;
        }
        site.activate();
        site = siteRepository.save(site);
        audit(site.getId(), SiteAuditLog.ACTION_ACTIVATED, byStaffId, null);
        return site;
    }

    @Transactional
    public PlatformTenantSite deactivate(UUID byStaffId, UUID siteId) {
        PlatformTenantSite site = require(siteId);
        if (!site.isActive()) {
            return site;
        }
        site.deactivate();
        site = siteRepository.save(site);
        audit(site.getId(), SiteAuditLog.ACTION_DEACTIVATED, byStaffId, null);
        return site;
    }

    // ----- helpers ----------------------------------------------------------

    private PlatformTenantSite require(UUID siteId) {
        return siteRepository.findById(siteId)
                .orElseThrow(() -> new NotFoundException("Site not found: " + siteId));
    }

    private void audit(UUID siteId, String action, UUID byStaffId, String detail) {
        auditRepository.save(new SiteAuditLog(siteId, action, byStaffId, detail));
    }

    private String generatePlaintextKey() {
        byte[] bytes = new byte[KEY_BODY_BYTES];
        random.nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String normaliseSubdomain(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase();
        return s.isEmpty() ? null : s;
    }

    private static String normaliseCustomDomain(String raw) {
        String s = normaliseSubdomain(raw);
        if (s == null) {
            return null;
        }
        if (!CUSTOM_DOMAIN.matcher(s).matches()) {
            throw new IllegalArgumentException(
                    "customDomain must be a bare domain (no scheme): " + raw);
        }
        return s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String renderDiff(Map<String, Object> diff) {
        StringBuilder sb = new StringBuilder();
        diff.forEach((field, vals) -> {
            String[] pair = (String[]) vals;
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(field).append(": ")
                    .append(pair[0] == null ? "null" : pair[0]).append(" → ")
                    .append(pair[1] == null ? "null" : pair[1]);
        });
        return sb.toString();
    }

    // ----- result records ---------------------------------------------------

    public record RegisterResult(PlatformTenantSite site, String plaintextKey) {
    }
}
