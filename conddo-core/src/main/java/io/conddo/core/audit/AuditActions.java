package io.conddo.core.audit;

/**
 * Canonical audit action names (stored in {@code audit_log.action}). Constants
 * rather than an enum so new modules can add their own without touching core.
 */
public final class AuditActions {

    public static final String LOGIN = "LOGIN";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String LOGOUT = "LOGOUT";
    public static final String SIGNUP_COMPLETED = "SIGNUP_COMPLETED";
    public static final String PASSWORD_RESET = "PASSWORD_RESET";
    public static final String CUSTOMER_CREATED = "CUSTOMER_CREATED";

    private AuditActions() {
    }
}
