package io.conddo.api.web;

import io.conddo.api.security.InMemoryRateLimiter;
import io.conddo.api.web.dto.PublicBookingRequest;
import io.conddo.api.web.dto.PublicBookingResponse;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.common.RateLimitExceededException;
import io.conddo.core.service.PublicBookingService;
import io.conddo.core.service.PublicBookingService.PublicAvailability;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PUBLIC, unauthenticated client-facing self-book endpoints (§11.5). Permitted
 * in {@code SecurityConfig}; the tenant is resolved from the link slug (not a
 * JWT). Rate-limited per client IP to deter abuse; sophisticated bot protection
 * (CAPTCHA / proof-of-work) is a follow-up. The {@code slug} is the tenant's
 * self-book slug (falls back to the tenant slug).
 */
@RestController
@RequestMapping("/api/v1/public/book")
public class PublicBookingController {

    private final PublicBookingService publicBookingService;
    private final InMemoryRateLimiter rateLimiter;

    public PublicBookingController(PublicBookingService publicBookingService, InMemoryRateLimiter rateLimiter) {
        this.publicBookingService = publicBookingService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/{slug}")
    public ApiResponse<PublicAvailability> availability(@PathVariable String slug, HttpServletRequest request) {
        rateLimit(request, "book-get");
        return ApiResponse.ok(publicBookingService.availability(slug));
    }

    @PostMapping("/{slug}")
    public ResponseEntity<ApiResponse<PublicBookingResponse>> book(
            @PathVariable String slug, @Valid @RequestBody PublicBookingRequest request,
            HttpServletRequest httpRequest) {
        rateLimit(httpRequest, "book-post");
        PublicBookingResponse body = PublicBookingResponse.from(publicBookingService.book(
                slug, request.customerName(), request.customerPhone(), request.service(), request.start()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    private void rateLimit(HttpServletRequest request, String bucket) {
        if (!rateLimiter.tryAcquire(clientIp(request) + ":" + bucket)) {
            throw new RateLimitExceededException("Too many requests — please try again shortly");
        }
    }

    /** Client IP, honouring the proxy's X-Forwarded-For (Render sits behind one). */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
