package io.conddo.core.auth;

/**
 * Tenant-facing roles (PRD §6.2). Stored as text in {@code users.role} and
 * carried in the access token's {@code role} claim. Mapped to Spring authorities
 * ({@code ROLE_<name>}) by the security layer.
 *
 * <p>Internal Handel Cores staff (e.g. SUPER_ADMIN) are a separate axis — see
 * {@link InternalRole} and {@code staff_users}.
 */
public enum Role {
    /** Owner/administrator of a business tenant. */
    TENANT_ADMIN,
    /** Staff member within a tenant. */
    STAFF,
    /** End customer of a tenant. */
    CUSTOMER
}
