package io.conddo.api.web;

import io.conddo.core.auth.AccountLockedException;
import io.conddo.core.auth.GoogleEmailUnverifiedException;
import io.conddo.core.auth.GoogleIdTokenInvalidException;
import io.conddo.core.auth.InvalidCredentialsException;
import io.conddo.core.auth.InvalidOtpException;
import io.conddo.core.auth.InvalidPasswordResetTokenException;
import io.conddo.core.auth.InvalidRefreshTokenException;
import io.conddo.core.auth.OtpThrottledException;
import io.conddo.core.auth.PhoneNotVerifiedException;
import io.conddo.core.auth.RegistrationNotFoundException;
import io.conddo.core.auth.UserAlreadyExistsException;
import io.conddo.core.auth.UserNotFoundException;
import io.conddo.api.publicapi.PublicCustomerAuth;
import io.conddo.api.publicapi.PublicSiteController;
import io.conddo.core.service.BrandPackageService;
import io.conddo.core.service.CreativeServiceService;
import io.conddo.core.service.MediaService;
import io.conddo.core.service.PrescriptionService;
import io.conddo.core.service.PublicCartService;
import io.conddo.core.service.PublicCustomerAuthService;
import io.conddo.core.service.PublicOrderCheckoutService;
import io.conddo.core.service.SocialMarketingService;
import io.conddo.core.service.TenantSiteService;
import io.conddo.core.common.ApiError;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.common.RateLimitExceededException;
import io.conddo.core.storage.StorageException;
import io.conddo.core.tenant.TenantContextMissingException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates exceptions into the standard error envelope (PRD §13.2).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiError.FieldError> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        ApiError error = ApiError.of("VALIDATION_ERROR", "Request validation failed", details);
        return ResponseEntity.badRequest().body(ApiResponse.fail(error));
    }

    /** A malformed typed path/query param (e.g. a non-UUID {id}) is a client error, not a 500. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ApiError.of("VALIDATION_ERROR", "Invalid value for '" + ex.getName() + "'")));
    }

    /**
     * Empty/malformed request body — {@code @RequestBody} can't deserialize
     * it. Without this handler Spring falls through to 500; the FE wires
     * (Seb&Bayor's login form, etc.) need a structured 400 to surface the
     * "fill in these fields" message.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(
                ApiError.of("BAD_REQUEST", "Request body is missing or malformed")));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ApiError.of("NOT_FOUND", ex.getMessage())));
    }

    @ExceptionHandler(TenantContextMissingException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoTenant(TenantContextMissingException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ApiError.of("NO_TENANT", ex.getMessage())));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail(ApiError.of("AUTH_INVALID_CREDENTIALS", ex.getMessage())));
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccountLocked(AccountLockedException ex) {
        return ResponseEntity.status(HttpStatus.LOCKED)
                .body(ApiResponse.fail(ApiError.of("AUTH_ACCOUNT_LOCKED", ex.getMessage())));
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail(ApiError.of("AUTH_INVALID_REFRESH_TOKEN", ex.getMessage())));
    }

    @ExceptionHandler(InvalidPasswordResetTokenException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidResetToken(InvalidPasswordResetTokenException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(ApiError.of("AUTH_INVALID_RESET_TOKEN", ex.getMessage())));
    }

    @ExceptionHandler(InvalidOtpException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidOtp(InvalidOtpException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(ApiError.of("AUTH_INVALID_OTP", ex.getMessage())));
    }

    @ExceptionHandler(OtpThrottledException.class)
    public ResponseEntity<ApiResponse<Void>> handleOtpThrottled(OtpThrottledException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResponse.fail(ApiError.of("AUTH_OTP_THROTTLED", ex.getMessage())));
    }

    @ExceptionHandler(RegistrationNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleRegistrationNotFound(RegistrationNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ApiError.of("AUTH_REGISTRATION_NOT_FOUND", ex.getMessage())));
    }

    @ExceptionHandler(PhoneNotVerifiedException.class)
    public ResponseEntity<ApiResponse<Void>> handlePhoneNotVerified(PhoneNotVerifiedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ApiError.of("AUTH_PHONE_NOT_VERIFIED", ex.getMessage())));
    }

    @ExceptionHandler(GoogleIdTokenInvalidException.class)
    public ResponseEntity<ApiResponse<Void>> handleGoogleIdTokenInvalid(GoogleIdTokenInvalidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(ApiError.of("GOOGLE_ID_TOKEN_INVALID", ex.getMessage())));
    }

    @ExceptionHandler(GoogleEmailUnverifiedException.class)
    public ResponseEntity<ApiResponse<Void>> handleGoogleEmailUnverified(GoogleEmailUnverifiedException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(ApiError.of("GOOGLE_EMAIL_UNVERIFIED", ex.getMessage())));
    }

    /** Single 404 for "no user matches" — see {@link UserNotFoundException} for the anti-enumeration note. */
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleUserNotFound(UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ApiError.of("USER_NOT_FOUND", ex.getMessage())));
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ApiResponse<Void>> handleUserAlreadyExists(UserAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ApiError.of("USER_ALREADY_EXISTS", ex.getMessage())));
    }

    /** Pharmacy refill reminder needs a phone — 422 per the spec. */
    @ExceptionHandler(PrescriptionService.NoCustomerPhoneException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoCustomerPhone(PrescriptionService.NoCustomerPhoneException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.fail(ApiError.of("no_customer_phone", ex.getMessage())));
    }

    /**
     * Method-level {@code @PreAuthorize} denials surface as AccessDeniedException
     * here (web-layer denials are handled earlier by the security filter chain).
     * Map both to a consistent 403 envelope.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail(ApiError.of("FORBIDDEN",
                        "You do not have permission to perform this action")));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimited(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResponse.fail(ApiError.of("RATE_LIMITED", ex.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ApiError.of("CONFLICT", ex.getMessage())));
    }

    /** An over-limit upload is a client error, not a 500. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.fail(ApiError.of("FILE_TOO_LARGE", "The uploaded file is too large")));
    }

    /** Object storage is unreachable/misconfigured — a bad gateway, not our bug. */
    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ApiResponse<Void>> handleStorage(StorageException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.fail(ApiError.of("STORAGE_ERROR",
                        "File storage is unavailable. Check the object-storage configuration.")));
    }

    /**
     * Public pharmacy order intake lost the stock race (WEBSITE_INTEGRATION_SPEC §3).
     * Body shape is fixed by the spec so the merchant's website can render a
     * "show out-of-stock" inline diff:
     * {@code {"error":"STOCK_SHORTAGE","items":[{productId,available,requested},...]} }.
     * Intentionally bypasses the {@link ApiResponse} envelope.
     */
    @ExceptionHandler(PublicSiteController.StockShortage.class)
    public ResponseEntity<Map<String, Object>> handleStockShortage(PublicSiteController.StockShortage ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "STOCK_SHORTAGE");
        body.put("items", ex.getItems());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /** Public-site call hit a feature gated off on the merchant's plan — 403. */
    @ExceptionHandler(PublicSiteController.ModuleNotEnabled.class)
    public ResponseEntity<ApiResponse<Void>> handleModuleNotEnabled(PublicSiteController.ModuleNotEnabled ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail(ApiError.of("MODULE_NOT_ENABLED", ex.getMessage())));
    }

    /** Tenant tried to claim a subdomain that violates the shared RFC-1035 rules. */
    @ExceptionHandler(TenantSiteService.InvalidSubdomainException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidSubdomain(TenantSiteService.InvalidSubdomainException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(ApiError.of("INVALID_SUBDOMAIN", ex.getMessage())));
    }

    /** Subdomain collides with another tenant's claim. */
    @ExceptionHandler(TenantSiteService.SubdomainTakenException.class)
    public ResponseEntity<ApiResponse<Void>> handleSubdomainTaken(TenantSiteService.SubdomainTakenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ApiError.of("SUBDOMAIN_TAKEN", ex.getMessage())));
    }

    /** Submitted URL failed the http/https scheme check. */
    @ExceptionHandler(TenantSiteService.InvalidSubmittedUrlException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidSubmittedUrl(TenantSiteService.InvalidSubmittedUrlException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(ApiError.of("INVALID_SUBMITTED_URL", ex.getMessage())));
    }

    /** Ayrshare env vars aren't wired in this deployment — clean 503 instead of a 500. */
    @ExceptionHandler(SocialMarketingService.SocialUnconfiguredException.class)
    public ResponseEntity<ApiResponse<Void>> handleSocialUnconfigured(SocialMarketingService.SocialUnconfiguredException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail(ApiError.of("SOCIAL_UNCONFIGURED", ex.getMessage())));
    }

    /** Ayrshare upstream rejected the call (returned no profileKey, no URL, etc.). */
    @ExceptionHandler(SocialMarketingService.AyrshareUpstreamException.class)
    public ResponseEntity<ApiResponse<Void>> handleAyrshareUpstream(SocialMarketingService.AyrshareUpstreamException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.fail(ApiError.of("AYRSHARE_UPSTREAM", ex.getMessage())));
    }

    /** Plan-tier media-storage cap hit (Phase 2a) — 413 with an explicit code. */
    @ExceptionHandler(MediaService.MediaStorageCapException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaStorageCap(MediaService.MediaStorageCapException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.fail(ApiError.of("MEDIA_STORAGE_CAP", ex.getMessage())));
    }

    /** conddo-payments is unreachable when a creative-service request was being created — 503. */
    @ExceptionHandler(CreativeServiceService.PaymentsUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handlePaymentsUnavailable(CreativeServiceService.PaymentsUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail(ApiError.of("PAYMENTS_UNAVAILABLE", ex.getMessage())));
    }

    /** Same shape for the brand-package subscribe path (Phase 3). */
    @ExceptionHandler(BrandPackageService.PaymentsUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleBrandPackagePaymentsUnavailable(BrandPackageService.PaymentsUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail(ApiError.of("PAYMENTS_UNAVAILABLE", ex.getMessage())));
    }

    /** Tenant already has a live brand-package subscription (Phase 3) — 409. */
    @ExceptionHandler(BrandPackageService.AlreadySubscribedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAlreadySubscribed(BrandPackageService.AlreadySubscribedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ApiError.of("ALREADY_SUBSCRIBED", ex.getMessage())));
    }

    /**
     * Bundle ride attempted but the quota for that creative-service code is
     * used up (Phase 3). 409 with the offering code + quota so the FE can
     * render "you've used your 4/4 designs this month" precisely.
     */
    @ExceptionHandler(BrandPackageService.QuotaExhaustedException.class)
    public ResponseEntity<ApiResponse<Void>> handleQuotaExhausted(BrandPackageService.QuotaExhaustedException ex) {
        java.util.LinkedHashMap<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("offeringCode", ex.getOfferingCode());
        details.put("quota", ex.getQuota());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ApiError.of("QUOTA_EXHAUSTED", ex.getMessage(),
                        java.util.List.of(new ApiError.FieldError("offeringCode",
                                "used " + ex.getQuota() + "/" + ex.getQuota())))));
    }

    /**
     * Public-website customer self-register tried an email already in use on
     * the same tenant (PHARMACY_PUBLIC_API_SPEC §2). 409 with EMAIL_TAKEN.
     */
    @ExceptionHandler(PublicCustomerAuthService.EmailAlreadyRegisteredException.class)
    public ResponseEntity<ApiResponse<Void>> handleCustomerEmailTaken(PublicCustomerAuthService.EmailAlreadyRegisteredException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ApiError.of("EMAIL_TAKEN", ex.getMessage())));
    }

    /** Public-website customer login: bad email or password — 401 INVALID_CREDENTIALS. */
    @ExceptionHandler(PublicCustomerAuthService.InvalidCustomerCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleCustomerInvalidCreds(PublicCustomerAuthService.InvalidCustomerCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail(ApiError.of("INVALID_CREDENTIALS", ex.getMessage())));
    }

    /** Missing or bad customer JWT on a public-website endpoint — 401 UNAUTHENTICATED. */
    @ExceptionHandler(PublicCustomerAuth.UnauthenticatedCustomerException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthenticatedCustomer(PublicCustomerAuth.UnauthenticatedCustomerException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail(ApiError.of("UNAUTHENTICATED", ex.getMessage())));
    }

    /** Public cart add: quantity exceeds available stock — 400 INSUFFICIENT_STOCK. */
    @ExceptionHandler(PublicCartService.InsufficientStockException.class)
    public ResponseEntity<ApiResponse<Void>> handleInsufficientStock(PublicCartService.InsufficientStockException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(ApiError.of("INSUFFICIENT_STOCK", ex.getMessage(),
                        java.util.List.of(
                                new ApiError.FieldError("productId", String.valueOf(ex.getProductId())),
                                new ApiError.FieldError("available", String.valueOf(ex.getAvailable())),
                                new ApiError.FieldError("requested", String.valueOf(ex.getRequested()))))));
    }

    /**
     * Public order checkout lost the stock race
     * (PHARMACY_PUBLIC_API_SPEC §5). Same shape as the merchant-website
     * STOCK_SHORTAGE — {@code {error, items[]}} outside the standard
     * envelope so the FE renders the inline diff.
     */
    @ExceptionHandler(PublicOrderCheckoutService.StockShortageException.class)
    public ResponseEntity<java.util.Map<String, Object>> handleCheckoutShortage(PublicOrderCheckoutService.StockShortageException ex) {
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("error", "OUT_OF_STOCK");
        body.put("items", ex.getItems());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** Order contains prescription items but no prescriptionId attached — 400 PRESCRIPTION_REQUIRED. */
    @ExceptionHandler(PublicOrderCheckoutService.PrescriptionRequiredException.class)
    public ResponseEntity<ApiResponse<Void>> handlePrescriptionRequired(PublicOrderCheckoutService.PrescriptionRequiredException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(ApiError.of("PRESCRIPTION_REQUIRED", ex.getMessage())));
    }

    /** Customer tried to fetch another customer's order — 403 (spec §5 detail). */
    @ExceptionHandler(PublicOrderCheckoutService.OrderNotYoursException.class)
    public ResponseEntity<ApiResponse<Void>> handleOrderNotYours(PublicOrderCheckoutService.OrderNotYoursException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail(ApiError.of("FORBIDDEN", ex.getMessage())));
    }

    /**
     * Launcher-plan tenants can't accept online orders — preserves the
     * Phase-1 gate now that the order flow runs through V33.
     */
    @ExceptionHandler(PublicOrderCheckoutService.ModuleNotEnabledException.class)
    public ResponseEntity<ApiResponse<Void>> handlePublicModuleNotEnabled(PublicOrderCheckoutService.ModuleNotEnabledException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail(ApiError.of("MODULE_NOT_ENABLED", ex.getMessage())));
    }

    /**
     * AI Product Assistant (Spec v2 §12C) — gateway is dormant on
     * this deployment because no API key is set. 503 with a clean
     * envelope so the FE can render "AI Assistant is offline" instead
     * of a generic 500.
     */
    @ExceptionHandler(io.conddo.core.ai.AnthropicGateway.AnthropicNotConfiguredException.class)
    public ResponseEntity<ApiResponse<Void>> handleAiNotConfigured(
            io.conddo.core.ai.AnthropicGateway.AnthropicNotConfiguredException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail(ApiError.of("AI_NOT_CONFIGURED", ex.getMessage())));
    }

    /**
     * AI Product Assistant — the call to Anthropic failed (timeout,
     * 5xx, non-JSON output). 502 with the gateway's sanitized
     * message; the underlying stack is logged via the catch-all
     * below.
     */
    @ExceptionHandler(io.conddo.core.ai.AnthropicGateway.AnthropicUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleAiUnavailable(
            io.conddo.core.ai.AnthropicGateway.AnthropicUnavailableException ex) {
        LOG.warn("AI gateway call failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.fail(ApiError.of("AI_UNAVAILABLE", ex.getMessage())));
    }

    /**
     * Catch-all for anything not handled above. We log the full stack
     * trace so Render logs surface the actual root cause (e.g. a
     * missing Flyway migration, a JPA query mismatch, a NPE), and we
     * include a sanitized exception class name in the response body so
     * the FE can show a useful toast and the cause can be eyeballed
     * without server log access (HANDOFF_2026-06-09b §4).
     *
     * <p>The class name is just the simple name (e.g.
     * {@code DataIntegrityViolationException}) — no stack frames,
     * no source paths. Enough to triage; not enough to leak internals.
     */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Throwable ex) {
        LOG.error("Unhandled exception bubbled to the global handler", ex);
        String cause = ex.getClass().getSimpleName();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ApiError.of("INTERNAL_ERROR",
                        "An unexpected error occurred (" + cause + ")")));
    }

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
}
