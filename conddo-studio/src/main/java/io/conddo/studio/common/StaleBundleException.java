package io.conddo.studio.common;

/**
 * §22.3 — manifest's recorded version is older than the current server-side
 * Site version, meaning someone else edited the job since export. Mapped to
 * {@code 409 STALE_BUNDLE} so the client can offer "force overwrite" or
 * "fetch newer and merge".
 */
public class StaleBundleException extends RuntimeException {
    private final int bundleVersion;
    private final int serverVersion;

    public StaleBundleException(int bundleVersion, int serverVersion) {
        super("Bundle was exported at version " + bundleVersion
                + " but the server is now at " + serverVersion);
        this.bundleVersion = bundleVersion;
        this.serverVersion = serverVersion;
    }

    public int getBundleVersion() {
        return bundleVersion;
    }

    public int getServerVersion() {
        return serverVersion;
    }
}
