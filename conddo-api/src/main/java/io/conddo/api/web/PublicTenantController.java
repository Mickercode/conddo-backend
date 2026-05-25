package io.conddo.api.web;

import io.conddo.api.security.InMemoryRateLimiter;
import io.conddo.api.security.SubdomainTenantResolver;
import io.conddo.api.web.dto.PublicTenantDto;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.common.RateLimitExceededException;
import io.conddo.core.domain.Tenant;
import io.conddo.core.repository.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * PUBLIC: resolves the current business from the request's subdomain
 * ({@code businessname.conddo.io}) and returns its public identity/branding
 * (§6.3). Backs the client-facing site/landing; permitted in SecurityConfig and
 * rate-limited. The same {@link SubdomainTenantResolver} will back the full
 * Website module and any subdomain-scoped public traffic later.
 */
@RestController
@RequestMapping("/api/v1/public/tenant")
public class PublicTenantController {

    private final SubdomainTenantResolver resolver;
    private final TenantRepository tenantRepository;
    private final InMemoryRateLimiter rateLimiter;

    public PublicTenantController(SubdomainTenantResolver resolver, TenantRepository tenantRepository,
                                  InMemoryRateLimiter rateLimiter) {
        this.resolver = resolver;
        this.tenantRepository = tenantRepository;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public ApiResponse<PublicTenantDto> current(HttpServletRequest request) {
        if (!rateLimiter.tryAcquire(clientIp(request) + ":public-tenant")) {
            throw new RateLimitExceededException("Too many requests — please try again shortly");
        }
        UUID tenantId = resolver.resolveTenantId(host(request))
                .orElseThrow(() -> new NotFoundException("No business found at this address"));
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new NotFoundException("No business found at this address"));
        return ApiResponse.ok(PublicTenantDto.from(tenant));
    }

    /** The request host, honouring the proxy's X-Forwarded-Host (Render/Nginx sit in front). */
    private static String host(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-Host");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String hostHeader = request.getHeader("Host");
        return hostHeader != null ? hostHeader : request.getServerName();
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
