package io.conddo.api.security;

import io.conddo.core.domain.Tenant;
import io.conddo.core.repository.TenantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves a tenant from the request host's subdomain (Architecture §6.3 / §11),
 * i.e. {@code businessname.conddo.io → tenant}. Built ahead of the domain so it
 * "just works" the moment {@code *.conddo.io} (wildcard DNS) is pointed at the
 * service and {@code CONDDO_BASE_DOMAIN} is set — no Nginx required, since the
 * app reads the forwarded Host header itself.
 *
 * <p>Reserved labels ({@code api}, {@code app}, …) never resolve. Lookups are
 * cached (slug→tenantId, short TTL, negatives included) to avoid a DB hit per
 * request; move to the Redis cache (§6.3) when Redis is provisioned. The
 * {@code tenants} table has no RLS, so resolution needs no tenant context.
 */
@Component
public class SubdomainTenantResolver {

    private static final Set<String> RESERVED =
            Set.of("api", "app", "www", "admin", "staff", "studio", "mail", "static", "cdn");
    private static final long TTL_MILLIS = 300_000L;   // 5 minutes (matches §6.3)

    private final TenantRepository tenantRepository;
    private final String baseDomain;
    private final ConcurrentMap<String, Cached> cache = new ConcurrentHashMap<>();

    public SubdomainTenantResolver(TenantRepository tenantRepository,
                                   @Value("${conddo.base-domain:conddo.io}") String baseDomain) {
        this.tenantRepository = tenantRepository;
        this.baseDomain = baseDomain.trim().toLowerCase();
    }

    /** Resolves the tenant id for a request host, or empty if the host isn't a known tenant subdomain. */
    public Optional<UUID> resolveTenantId(String host) {
        String slug = subdomainOf(host);
        if (slug == null) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        Cached cached = cache.get(slug);
        if (cached == null || now > cached.expiresAt()) {
            UUID tenantId = tenantRepository.findBySlug(slug).map(Tenant::getId).orElse(null);
            cached = new Cached(tenantId, now + TTL_MILLIS);
            cache.put(slug, cached);
        }
        return Optional.ofNullable(cached.tenantId());
    }

    /** Extracts the single-label tenant subdomain from a host, or null. Package-visible for tests. */
    String subdomainOf(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String h = host.trim().toLowerCase();
        int colon = h.indexOf(':');
        if (colon >= 0) {
            h = h.substring(0, colon);
        }
        String suffix = "." + baseDomain;
        if (!h.endsWith(suffix) || h.length() == suffix.length()) {
            return null;
        }
        String sub = h.substring(0, h.length() - suffix.length());
        if (sub.isEmpty() || sub.contains(".") || RESERVED.contains(sub)) {
            return null;
        }
        return sub;
    }

    private record Cached(UUID tenantId, long expiresAt) {
    }
}
