package io.conddo.api.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Module access matrix for STAFF sub-roles (HANDOFF_2026-06-12 §4).
 * Owners and SUPER_ADMIN bypass every check; STAFF users are gated
 * by their {@code staffRole} JWT claim through the per-module
 * matrix below.
 *
 * <pre>
 * &#64;PreAuthorize("&#64;staffAccess.canWrite('inventory')")
 * &#64;PostMapping("/restock")
 * public ResponseEntity&lt;...&gt; restock(...) { ... }
 * </pre>
 *
 * <p>Single source of truth lives here (BE). The FE catalogue
 * (lib/api/staff.ts → STAFF_ROLE_CATALOGUE) mirrors this matrix
 * for UI nav decisions; on a divergence the BE is authoritative
 * because it controls the 403.
 */
@Component("staffAccess")
public class StaffAccess {

    public enum Permission {
        NONE, READ, WRITE;

        boolean reads() {
            return this != NONE;
        }

        boolean writes() {
            return this == WRITE;
        }
    }

    private static final Map<String, Map<String, Permission>> MATRIX = Map.ofEntries(
            // MANAGER — second-in-command. Everything except billing + staff invites.
            Map.entry("MANAGER", Map.ofEntries(
                    Map.entry("inventory", Permission.WRITE),
                    Map.entry("orders", Permission.WRITE),
                    Map.entry("payments", Permission.WRITE),
                    Map.entry("customers", Permission.WRITE),
                    Map.entry("analytics", Permission.WRITE),
                    Map.entry("prescriptions", Permission.WRITE),
                    Map.entry("consultations", Permission.WRITE),
                    Map.entry("pos", Permission.WRITE),
                    Map.entry("emr", Permission.WRITE),
                    Map.entry("marketing", Permission.WRITE),
                    Map.entry("followups", Permission.WRITE),
                    Map.entry("loyalty", Permission.WRITE),
                    Map.entry("staff", Permission.NONE),
                    Map.entry("billing", Permission.NONE))),
            // PHARMACIST — clinical surface.
            Map.entry("PHARMACIST", Map.ofEntries(
                    Map.entry("inventory", Permission.READ),
                    Map.entry("orders", Permission.READ),
                    Map.entry("customers", Permission.READ),
                    Map.entry("analytics", Permission.READ),
                    Map.entry("prescriptions", Permission.WRITE),
                    Map.entry("consultations", Permission.WRITE),
                    Map.entry("emr", Permission.WRITE),
                    Map.entry("followups", Permission.WRITE),
                    Map.entry("pos", Permission.READ),
                    Map.entry("loyalty", Permission.READ),
                    Map.entry("payments", Permission.NONE),
                    Map.entry("marketing", Permission.NONE),
                    Map.entry("staff", Permission.NONE),
                    Map.entry("billing", Permission.NONE))),
            // CASHIER — POS sales + read-only customers.
            Map.entry("CASHIER", Map.ofEntries(
                    Map.entry("pos", Permission.WRITE),
                    Map.entry("customers", Permission.READ),
                    Map.entry("inventory", Permission.READ),
                    Map.entry("orders", Permission.READ),
                    Map.entry("payments", Permission.READ),
                    Map.entry("prescriptions", Permission.READ),
                    Map.entry("loyalty", Permission.READ),
                    Map.entry("analytics", Permission.NONE),
                    Map.entry("consultations", Permission.NONE),
                    Map.entry("emr", Permission.NONE),
                    Map.entry("followups", Permission.NONE),
                    Map.entry("marketing", Permission.NONE),
                    Map.entry("staff", Permission.NONE),
                    Map.entry("billing", Permission.NONE))),
            // STOCK_MANAGER — inventory + reconciliation + restock + bulk upload.
            Map.entry("STOCK_MANAGER", Map.ofEntries(
                    Map.entry("inventory", Permission.WRITE),
                    Map.entry("analytics", Permission.READ),
                    Map.entry("orders", Permission.READ),
                    Map.entry("customers", Permission.READ),
                    Map.entry("payments", Permission.NONE),
                    Map.entry("pos", Permission.NONE),
                    Map.entry("prescriptions", Permission.NONE),
                    Map.entry("consultations", Permission.NONE),
                    Map.entry("emr", Permission.NONE),
                    Map.entry("followups", Permission.NONE),
                    Map.entry("loyalty", Permission.NONE),
                    Map.entry("marketing", Permission.NONE),
                    Map.entry("staff", Permission.NONE),
                    Map.entry("billing", Permission.NONE))),
            // BOOKKEEPER — read-only revenue surfaces + CSV exports.
            Map.entry("BOOKKEEPER", Map.ofEntries(
                    Map.entry("orders", Permission.READ),
                    Map.entry("payments", Permission.READ),
                    Map.entry("analytics", Permission.READ),
                    Map.entry("customers", Permission.READ),
                    Map.entry("inventory", Permission.NONE),
                    Map.entry("pos", Permission.NONE),
                    Map.entry("prescriptions", Permission.NONE),
                    Map.entry("consultations", Permission.NONE),
                    Map.entry("emr", Permission.NONE),
                    Map.entry("followups", Permission.NONE),
                    Map.entry("loyalty", Permission.NONE),
                    Map.entry("marketing", Permission.NONE),
                    Map.entry("staff", Permission.NONE),
                    Map.entry("billing", Permission.NONE))));

    public boolean canRead(String module) {
        return permissionFor(module).reads();
    }

    public boolean canWrite(String module) {
        return permissionFor(module).writes();
    }

    /** Owner-only convenience (used for billing, staff invites, discount approval, POS void). */
    public boolean ownerOnly() {
        String role = currentPlatformRole();
        return "TENANT_ADMIN".equals(role) || "SUPER_ADMIN".equals(role);
    }

    /** Owner or a specific staff sub-role; used for cases like EMR notes (PHARMACIST+MANAGER+owner). */
    public boolean ownerOr(String... staffRoles) {
        if (ownerOnly()) {
            return true;
        }
        String myStaffRole = currentStaffRole();
        if (myStaffRole == null) {
            return false;
        }
        for (String role : staffRoles) {
            if (myStaffRole.equals(role)) {
                return true;
            }
        }
        return false;
    }

    // ----- internals ---------------------------------------------------------

    private Permission permissionFor(String module) {
        String role = currentPlatformRole();
        if ("SUPER_ADMIN".equals(role) || "TENANT_ADMIN".equals(role)) {
            return Permission.WRITE;
        }
        if (!"STAFF".equals(role)) {
            return Permission.NONE;
        }
        String staffRole = currentStaffRole();
        if (staffRole == null) {
            return Permission.NONE;
        }
        Map<String, Permission> moduleMap = MATRIX.get(staffRole);
        if (moduleMap == null) {
            return Permission.NONE;
        }
        return moduleMap.getOrDefault(module, Permission.NONE);
    }

    private String currentPlatformRole() {
        Jwt jwt = currentJwt();
        return jwt == null ? null : jwt.getClaimAsString("role");
    }

    private String currentStaffRole() {
        Jwt jwt = currentJwt();
        return jwt == null ? null : jwt.getClaimAsString("staffRole");
    }

    private Jwt currentJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        Object principal = auth.getPrincipal();
        return principal instanceof Jwt jwt ? jwt : null;
    }
}
