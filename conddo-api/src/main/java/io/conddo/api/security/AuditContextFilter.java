package io.conddo.api.security;

import io.conddo.core.audit.AuditContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Captures the client IP and user-agent onto {@link AuditContext} for the
 * request, clearing the whole context afterwards. Added early (outermost) in the
 * chain so its finally runs after the JWT filter has set the actor — one place
 * owns the context lifecycle.
 *
 * <p>IP comes from {@code X-Forwarded-For} first, since the app runs behind a
 * proxy (Render) where {@code getRemoteAddr()} is the proxy, not the client.
 */
public class AuditContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            AuditContext.setRequest(clientIp(request), request.getHeader("User-Agent"));
            filterChain.doFilter(request, response);
        } finally {
            AuditContext.clear();
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
