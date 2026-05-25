package io.conddo.studio.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Authenticates the platform → Studio job hand-off (SERVICE_TOPOLOGY.md §4). For
 * {@code /api/jobs/intake} requests carrying a matching {@code X-Studio-Service-Token},
 * it sets a {@code ROLE_SERVICE} principal; otherwise it does nothing and the
 * normal JWT chain / entry point rejects the request. This is a machine principal,
 * deliberately separate from staff STUDIO_JWTs.
 *
 * <p>Fails closed: when no token is configured, nothing ever authenticates here.
 * The compare is constant-time.
 */
public class StudioServiceTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Studio-Service-Token";
    public static final String ROLE = "ROLE_SERVICE";
    private static final String INTAKE_PREFIX = "/api/jobs/intake";

    private final byte[] expectedToken;

    public StudioServiceTokenFilter(String configuredToken) {
        this.expectedToken = configuredToken == null || configuredToken.isBlank()
                ? null : configuredToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // getServletPath() is unreliable under MockMvc/root-mapped servlets; derive
        // the app-relative path from the request URI minus the context path.
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith(INTAKE_PREFIX) && matches(request.getHeader(HEADER))) {
            var auth = new UsernamePasswordAuthenticationToken(
                    "studio-service", null, List.of(new SimpleGrantedAuthority(ROLE)));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }

    private boolean matches(String presented) {
        return expectedToken != null && presented != null
                && MessageDigest.isEqual(expectedToken, presented.getBytes(StandardCharsets.UTF_8));
    }
}
