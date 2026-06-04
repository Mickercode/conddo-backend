package io.conddo.payments.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.payments.common.ApiError;
import io.conddo.payments.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates inbound {@code /api/payments/internal/**} requests by matching
 * a header secret with {@code payments.service.token}. Same shape as Studio's
 * {@code StudioServiceTokenFilter} — service-to-service trust without minting
 * a fake JWT. Other paths pass through unaltered for the JWT chain to handle.
 *
 * <p>When the token is configured but the inbound header is missing or wrong,
 * we short-circuit the chain with a 401 envelope; an unconfigured token
 * (blank) means the internal surface is intentionally off in this environment.
 */
@Component
public class PaymentsServiceTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Payments-Service-Token";
    public static final String ROLE = "SERVICE";

    private final String expectedToken;
    private final ObjectMapper objectMapper;

    public PaymentsServiceTokenFilter(@Value("${payments.service.token:}") String expectedToken,
                                      ObjectMapper objectMapper) {
        this.expectedToken = expectedToken == null ? "" : expectedToken;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/payments/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (expectedToken.isBlank()) {
            writeUnauthorised(response,
                    "Internal payments endpoints are disabled (PAYMENTS_SERVICE_TOKEN not set)");
            return;
        }
        String header = request.getHeader(HEADER);
        if (header == null || !expectedToken.equals(header)) {
            writeUnauthorised(response, "Invalid or missing " + HEADER);
            return;
        }
        // Stamp a SERVICE authority so the @PreAuthorize on the internal controller works.
        var auth = new AnonymousAuthenticationToken("payments-service", "service-token",
                List.of(new SimpleGrantedAuthority("ROLE_" + ROLE)));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void writeUnauthorised(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.fail(ApiError.of("UNAUTHENTICATED", message)));
    }
}
