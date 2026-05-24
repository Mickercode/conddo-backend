package io.conddo.core.auth;

/**
 * Roles on the <em>internal</em> (Handel Cores staff) axis — distinct from the
 * tenant-facing {@link Role}. Stored in {@code staff_users.internal_role} and
 * carried in the access token's {@code role} claim, so {@code @PreAuthorize}
 * checks are uniform across both axes.
 *
 * <p>Only {@link #SUPER_ADMIN} is wired in Phase 1. The Conddo Studio roles
 * (PRD v1.3 §22, Phase 3) are declared so the axis is explicit, but are not yet
 * used anywhere.
 */
public enum InternalRole {
    /** Platform administrator; may act on any tenant via X-Act-As-Tenant. */
    SUPER_ADMIN,
    /** Conddo Studio — builds tenant websites (Phase 3, not yet wired). */
    WEBSITE_DEVELOPER,
    /** Conddo Studio — reviews submitted builds (Phase 3, not yet wired). */
    QA_REVIEWER,
    /** Conddo Studio — assigns work and manages capacity (Phase 3, not yet wired). */
    PRODUCTION_TEAM_LEAD
}
