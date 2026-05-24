package io.conddo.core.audit;

import java.util.Optional;
import java.util.UUID;

/**
 * Per-request audit metadata on a thread-local: client IP, user-agent, and the
 * authenticated actor. Populated at the edge (an audit filter sets IP/UA; the
 * JWT filter sets the actor) and read by {@link AuditService}. Mirrors
 * {@code TenantContext}; the security types stay in the web layer so core only
 * ever sees plain values.
 */
public final class AuditContext {

    private static final ThreadLocal<String> IP_ADDRESS = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_AGENT = new ThreadLocal<>();
    private static final ThreadLocal<UUID> ACTOR = new ThreadLocal<>();

    private AuditContext() {
    }

    public static void setRequest(String ipAddress, String userAgent) {
        IP_ADDRESS.set(ipAddress);
        USER_AGENT.set(userAgent);
    }

    public static void setActor(UUID userId) {
        ACTOR.set(userId);
    }

    public static String getIpAddress() {
        return IP_ADDRESS.get();
    }

    public static String getUserAgent() {
        return USER_AGENT.get();
    }

    public static Optional<UUID> getActor() {
        return Optional.ofNullable(ACTOR.get());
    }

    public static void clear() {
        IP_ADDRESS.remove();
        USER_AGENT.remove();
        ACTOR.remove();
    }
}
