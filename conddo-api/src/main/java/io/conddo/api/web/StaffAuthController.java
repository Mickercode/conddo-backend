package io.conddo.api.web;

import io.conddo.api.web.dto.LoginResponse;
import io.conddo.api.web.dto.StaffLoginRequest;
import io.conddo.core.auth.StaffAuthResult;
import io.conddo.core.auth.StaffAuthService;
import io.conddo.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal-staff authentication (PRD v1.3 §22), separate from tenant
 * {@link AuthController}. Returns an access token with no tenant_id and no
 * refresh cookie (staff refresh is deferred to Phase 3). SUPER_ADMIN then scopes
 * to a tenant per request via the {@code X-Act-As-Tenant} header.
 */
@RestController
@RequestMapping("/auth/staff")
public class StaffAuthController {

    private final StaffAuthService staffAuthService;

    public StaffAuthController(StaffAuthService staffAuthService) {
        this.staffAuthService = staffAuthService;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody StaffLoginRequest request) {
        StaffAuthResult result = staffAuthService.login(request.email(), request.password());
        LoginResponse body = new LoginResponse(result.accessToken(), "Bearer",
                result.accessTokenTtl().toSeconds(), result.staffId(), result.internalRole());
        return ResponseEntity.ok(ApiResponse.ok(body));
    }
}
