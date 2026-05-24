package io.conddo.api.web;

import io.conddo.api.security.RefreshCookies;
import io.conddo.api.web.dto.CompleteRegistrationRequest;
import io.conddo.api.web.dto.LoginResponse;
import io.conddo.api.web.dto.RegisterStartRequest;
import io.conddo.api.web.dto.RegisterStartResponse;
import io.conddo.api.web.dto.ResendOtpRequest;
import io.conddo.api.web.dto.VerifyOtpRequest;
import io.conddo.core.auth.AuthResult;
import io.conddo.core.auth.RegistrationService;
import io.conddo.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staged, phone-verified signup wizard (PRD §6.2). Public ({@code /auth/**}).
 * start → verify/resend → complete; completion creates the tenant + admin and
 * logs the user in (access token in body, refresh token in the cookie) so the
 * frontend lands on the dashboard authenticated.
 */
@RestController
@RequestMapping("/auth/register")
public class RegistrationController {

    private final RegistrationService registrationService;
    private final RefreshCookies refreshCookies;

    public RegistrationController(RegistrationService registrationService, RefreshCookies refreshCookies) {
        this.registrationService = registrationService;
        this.refreshCookies = refreshCookies;
    }

    @PostMapping("/start")
    public ResponseEntity<ApiResponse<RegisterStartResponse>> start(@Valid @RequestBody RegisterStartRequest request) {
        RegistrationService.StartResult result = registrationService.start(
                request.fullName(), request.phone(), request.email(), request.password());
        return ResponseEntity.ok(ApiResponse.ok(RegisterStartResponse.from(result)));
    }

    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<Void>> verify(@Valid @RequestBody VerifyOtpRequest request) {
        registrationService.verify(request.registrationId(), request.code());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/resend")
    public ResponseEntity<ApiResponse<RegisterStartResponse>> resend(@Valid @RequestBody ResendOtpRequest request) {
        long cooldown = registrationService.resend(request.registrationId());
        return ResponseEntity.ok(ApiResponse.ok(new RegisterStartResponse(request.registrationId(), cooldown)));
    }

    @PostMapping("/complete")
    public ResponseEntity<ApiResponse<LoginResponse>> complete(@Valid @RequestBody CompleteRegistrationRequest request) {
        AuthResult result = registrationService.complete(
                request.registrationId(), request.businessName(), request.businessType(), request.planId());
        ResponseCookie cookie = refreshCookies.issue(result.refreshToken(), result.refreshTokenTtl());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.ok(LoginResponse.from(result)));
    }
}
