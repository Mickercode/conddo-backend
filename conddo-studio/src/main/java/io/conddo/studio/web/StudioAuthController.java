package io.conddo.studio.web;

import io.conddo.studio.auth.StudioAuthService;
import io.conddo.studio.auth.StudioPrincipal;
import io.conddo.studio.common.ApiResponse;
import io.conddo.studio.staff.StaffService;
import io.conddo.studio.web.dto.AuthResponse;
import io.conddo.studio.web.dto.RefreshRequest;
import io.conddo.studio.web.dto.StaffDto;
import io.conddo.studio.web.dto.StaffLoginRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Studio staff authentication (Infrastructure §13.2). HMAC STUDIO_JWT + opaque refresh. */
@RestController
@RequestMapping("/api/jobs/auth")
public class StudioAuthController {

    private final StudioAuthService authService;
    private final StaffService staffService;

    public StudioAuthController(StudioAuthService authService, StaffService staffService) {
        this.authService = authService;
        this.staffService = staffService;
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody StaffLoginRequest request) {
        return ApiResponse.ok(AuthResponse.from(authService.login(request.email(), request.password())));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(AuthResponse.from(authService.refresh(request.refreshToken())));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ApiResponse<StaffDto> me(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(StaffDto.from(staffService.get(StudioPrincipal.staffId(jwt))));
    }
}
