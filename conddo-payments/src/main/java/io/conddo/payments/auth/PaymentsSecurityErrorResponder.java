package io.conddo.payments.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.payments.common.ApiError;
import io.conddo.payments.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Unifies 401 + 403 responses behind the standard envelope. Without this,
 * Spring Security's defaults serve a bare HTML error page that breaks the
 * FE's {@code success/error} contract.
 */
@Component
public class PaymentsSecurityErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public PaymentsSecurityErrorResponder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException ex) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException ex) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.fail(ApiError.of(code, message)));
    }
}
