package io.conddo.payments.web;

import io.conddo.payments.common.ApiError;
import io.conddo.payments.common.ApiResponse;
import io.conddo.payments.common.ConflictException;
import io.conddo.payments.common.NotFoundException;
import io.conddo.payments.common.RoutePayUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/** Translates payments exceptions into the standard envelope. */
@RestControllerAdvice
public class PaymentsExceptionHandler {

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
                .body(ApiResponse.fail(ApiError.of("VALIDATION_ERROR",
                        "Invalid value for '" + ex.getName() + "'")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ApiError.of("BAD_REQUEST", ex.getMessage())));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ApiError.of("NOT_FOUND", ex.getMessage())));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(ApiError.of("CONFLICT", ex.getMessage())));
    }

    @ExceptionHandler(RoutePayUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleRoutePayDown(RoutePayUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.fail(ApiError.of("ROUTEPAY_UNAVAILABLE",
                        "Payments are temporarily unavailable, please try again")));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail(ApiError.of("FORBIDDEN", "Access denied")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ApiError.of("INTERNAL_ERROR", "An unexpected error occurred")));
    }
}
