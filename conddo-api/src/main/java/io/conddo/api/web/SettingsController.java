package io.conddo.api.web;

import io.conddo.api.web.dto.BrandingRequest;
import io.conddo.api.web.dto.BusinessProfileRequest;
import io.conddo.api.web.dto.BusinessProfileResponse;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.SettingsService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Tenant settings (§11.11) — admin only. Business profile, branding, social
 * handles, location, business hours, notification preferences, and the Danger
 * Zone deactivate. Tenant comes from the JWT (RLS). Subscription/billing,
 * API keys, and tenant deletion are deferred to their own modules (§7 Billing).
 */
@RestController
@RequestMapping("/api/v1/settings")
@PreAuthorize("hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/business-profile")
    public ApiResponse<BusinessProfileResponse> businessProfile() {
        return ApiResponse.ok(BusinessProfileResponse.from(settingsService.businessProfile()));
    }

    @PutMapping("/business-profile")
    public ApiResponse<BusinessProfileResponse> updateBusinessProfile(
            @Valid @RequestBody BusinessProfileRequest request) {
        return ApiResponse.ok(BusinessProfileResponse.from(settingsService.updateBusinessProfile(
                request.name(), request.tagline(), request.description(), request.email(), request.phone())));
    }

    @PutMapping("/branding")
    public ApiResponse<BrandingRequest> updateBranding(@RequestBody BrandingRequest request) {
        return ApiResponse.ok(BrandingRequest.from(
                settingsService.updateBranding(request.primaryColor(), request.logoUrl())));
    }

    @PutMapping("/social-handles")
    public ApiResponse<Map<String, Object>> updateSocialHandles(@RequestBody Map<String, Object> handles) {
        return ApiResponse.ok(settingsService.updateSocialHandles(handles));
    }

    @PutMapping("/location")
    public ApiResponse<Map<String, Object>> updateLocation(@RequestBody Map<String, Object> location) {
        return ApiResponse.ok(settingsService.updateLocation(location));
    }

    @GetMapping("/business-hours")
    public ApiResponse<Map<String, Object>> businessHours() {
        return ApiResponse.ok(settingsService.businessHours());
    }

    @PutMapping("/business-hours")
    public ApiResponse<Map<String, Object>> updateBusinessHours(@RequestBody Map<String, Object> hours) {
        return ApiResponse.ok(settingsService.updateBusinessHours(hours));
    }

    @GetMapping("/notifications")
    public ApiResponse<Map<String, Object>> notifications() {
        return ApiResponse.ok(settingsService.notifications());
    }

    @PutMapping("/notifications")
    public ApiResponse<Map<String, Object>> updateNotifications(@RequestBody Map<String, Object> prefs) {
        return ApiResponse.ok(settingsService.updateNotifications(prefs));
    }

    @PostMapping("/danger/deactivate")
    public ApiResponse<BusinessProfileResponse> deactivate() {
        return ApiResponse.ok(BusinessProfileResponse.from(settingsService.deactivate()));
    }
}
