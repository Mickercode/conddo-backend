package io.conddo.studio.common;

/**
 * §22.3 — recomputed SHA-256 didn't match the manifest's claimed checksum.
 * Mapped to {@code 422 BUNDLE_TAMPERED}.
 */
public class BundleTamperedException extends RuntimeException {
    public BundleTamperedException() {
        super("Bundle integrity check failed (checksum mismatch)");
    }
}
