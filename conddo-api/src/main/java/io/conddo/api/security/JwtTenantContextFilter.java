package io.conddo.api.security;

import io.conddo.core.audit.AuditContext;
import io.conddo.core.auth.JwtService;
import io.conddo.core.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Once the resource server has authenticated the bearer token, copies the JWT's
 * {@code tenant_id} claim onto {@link TenantContext} so PostgreSQL RLS scopes the
 * request — then clears it in a finally so nothing leaks across pooled threads.
 *
 * <p>This replaces the Phase 0 {@code X-Tenant-Id} header: the tenant now comes
 * from the signed token, which the client cannot forge. Requests without a JWT
 * (public endpoints) simply carry no tenant; tenant-scoped work then fails closed.
 *
 * <p><b>SUPER_ADMIN act-as:</b> platform staff belong to the sentinel platform
 * tenant and have no business data of their own. They may operate on a specific
 * tenant by sending {@code X-Act-As-Tenant: <uuid>}; the app role stays non-owner
 * and RLS still fully applies — they simply scope to one tenant at a time, never
 * bypassing isolation. The header is honoured ONLY for SUPER_ADMIN; for everyone
 * else it is ignored, so it cannot be used to escape one's own tenant.
 */
public class JwtTenantContextFilter extends OncePerRequestFilter {

    public static final String ACT_AS_TENANT_HEADER = "X-Act-As-Tenant";
    private static final String SUPER_ADMIN_AUTHORITY = "ROLE_SUPER_ADMIN";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof JwtAuthenticationToken jwtAuth) {
                UUID tenantId = parseUuid(jwtAuth.getToken().getClaimAsString(JwtService.CLAIM_TENANT_ID));
                if (isSuperAdmin(jwtAuth)) {
                    UUID actAs = parseUuid(request.getHeader(ACT_AS_TENANT_HEADER));
                    if (actAs != null) {
                        tenantId = actAs;
                    }
                }
                if (tenantId != null) {
                    TenantContext.set(tenantId);
                }
                // The token subject is the user id — record it as the audit actor.
                AuditContext.setActor(parseUuid(jwtAuth.getToken().getSubject()));
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static boolean isSuperAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> SUPER_ADMIN_AUTHORITY.equals(authority.getAuthority()));
    }

    /** Returns the parsed UUID, or null if the value is absent or malformed (fails closed). */
    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
