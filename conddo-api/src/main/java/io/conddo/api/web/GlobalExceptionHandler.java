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
import io.conddo.core.service.PrescriptionService;
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

import java.util.List;

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ApiError.of("INTERNAL_ERROR", "An unexpected error occurred")));
    }
}
