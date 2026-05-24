package io.conddo.api.web;

import io.conddo.api.web.dto.MeResponse;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.MeService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * {@code GET /api/v1/me} — the current user + tenant for the dashboard shell
 * (§11.12). The user id is the JWT subject; the tenant comes from the bound
 * tenant context.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final MeService meService;

    public MeController(MeService meService) {
        this.meService = meService;
    }

    @GetMapping
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal Jwt jwt) {
        MeService.Identity identity = meService.current(UUID.fromString(jwt.getSubject()));
        return ApiResponse.ok(MeResponse.from(identity.user(), identity.tenant()));
    }
}
