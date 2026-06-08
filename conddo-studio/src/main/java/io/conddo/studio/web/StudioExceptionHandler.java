package io.conddo.studio.web;

import io.conddo.studio.common.ApiError;
import io.conddo.studio.common.ApiResponse;
import io.conddo.studio.common.BundleTamperedException;
import io.conddo.studio.common.ConflictException;
import io.conddo.studio.common.HomePageRequiredException;
import io.conddo.studio.common.InvalidCredentialsException;
import io.conddo.studio.common.JobMismatchException;
import io.conddo.studio.common.LastAdminProtectedException;
import io.conddo.studio.common.NotFoundException;
import io.conddo.studio.common.StaleBundleException;
import io.conddo.studio.common.VersionMismatchException;
import io.conddo.studio.storage.StorageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;

/** Translates Studio exceptions into the standard envelope. */
@RestControllerAdvice
public class StudioExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiError.FieldError> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ApiError.of("VALIDATION_ERROR", "Request validation failed", details)));
    }

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

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ApiError.of(ex.getCode(), ex.getMessage())));
    }

    /** §23.5 — refuse to leave a tenant without any active TENANT_ADMIN. */
    @ExceptionHandler(LastAdminProtectedException.class)
    public ResponseEntity<ApiResponse<Void>> handleLastAdmin(LastAdminProtectedException ex) {
        return ResponseEntity.unprocessableEntity()
                .body(ApiResponse.fail(ApiError.of("LAST_ADMIN_PROTECTED", ex.getMessage())));
    }

    /** §21.3 — every site needs a home page; deleting the only home page is refused. */
    @ExceptionHandler(HomePageRequiredException.class)
    public ResponseEntity<ApiResponse<Void>> handleHomePage(HomePageRequiredException ex) {
        return ResponseEntity.unprocessableEntity()
                .body(ApiResponse.fail(ApiError.of("HOME_PAGE_REQUIRED", ex.getMessage())));
    }

    /** §21.3 — If-Match version mismatch on a Site mutation. */
    @ExceptionHandler(VersionMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleVersionMismatch(VersionMismatchException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ApiError.of("VERSION_MISMATCH", ex.getMessage(),
                        List.of(new ApiError.FieldError("expected", String.valueOf(ex.getExpected())),
                                new ApiError.FieldError("actual", String.valueOf(ex.getActual()))))));
    }

    /** Hibernate's optimistic-lock failure on concurrent writes — mirror to VERSION_MISMATCH. */
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleHibernateOptimisticLock(
            org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ApiError.of("VERSION_MISMATCH",
                        "Concurrent edit detected — refetch the site and retry")));
    }

    // ----- Import/Export (§22) ------------------------------------------------

    @ExceptionHandler(BundleTamperedException.class)
    public ResponseEntity<ApiResponse<Void>> handleBundleTampered(BundleTamperedException ex) {
        return ResponseEntity.unprocessableEntity()
                .body(ApiResponse.fail(ApiError.of("BUNDLE_TAMPERED", ex.getMessage())));
    }

    @ExceptionHandler(JobMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleJobMismatch(JobMismatchException ex) {
        return ResponseEntity.unprocessableEntity()
                .body(ApiResponse.fail(ApiError.of("JOB_MISMATCH", ex.getMessage())));
    }

    @ExceptionHandler(StaleBundleException.class)
    public ResponseEntity<ApiResponse<Void>> handleStaleBundle(StaleBundleException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ApiError.of("STALE_BUNDLE", ex.getMessage(),
                        List.of(new ApiError.FieldError("bundleVersion", String.valueOf(ex.getBundleVersion())),
                                new ApiError.FieldError("serverVersion", String.valueOf(ex.getServerVersion()))))));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail(ApiError.of("INVALID_CREDENTIALS", ex.getMessage())));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail(ApiError.of("FORBIDDEN",
                        "You do not have permission to perform this action")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ApiError.of("BAD_REQUEST", ex.getMessage())));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.fail(ApiError.of("FILE_TOO_LARGE", "The uploaded file is too large")));
    }

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ApiResponse<Void>> handleStorage(StorageException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.fail(ApiError.of("STORAGE_ERROR",
                        "Asset storage is unavailable. Check the Cloudinary configuration.")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ApiError.of("INTERNAL_ERROR", "An unexpected error occurred")));
    }
}
