package io.conddo.api.web.dto;

import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.User;

import java.util.UUID;

/**
 * The dashboard shell identity (§11.12 {@code GET /api/v1/me}): the signed-in
 * user and their business, used for the sidebar (business name, user name/role,
 * initials, subdomain).
 */
public record MeResponse(UserSummary user, TenantSummary tenant) {

    public record UserSummary(UUID id, String fullName, String email, String role, String initials) {
    }

    public record TenantSummary(UUID id, String name, String slug, String subdomain,
                                String customDomain, String verticalId, String planId, String status) {
    }

    public static MeResponse from(User user, Tenant tenant) {
        UserSummary userSummary = new UserSummary(
                user.getId(), user.getFullName(), user.getEmail(), user.getRole(), initials(user.getFullName()));
        TenantSummary tenantSummary = new TenantSummary(
                tenant.getId(), tenant.getName(), tenant.getSlug(), tenant.getSlug(),
                tenant.getCustomDomain(), tenant.getVerticalId(), tenant.getPlanId(), tenant.getStatus());
        return new MeResponse(userSummary, tenantSummary);
    }

    /** "Amaka Styles" → "AS"; "Amaka" → "AM"; blank → "?". */
    private static String initials(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "?";
        }
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length >= 2) {
            return ("" + parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase();
        }
        String one = parts[0];
        return (one.length() >= 2 ? one.substring(0, 2) : one).toUpperCase();
    }
}
