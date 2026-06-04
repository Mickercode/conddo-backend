package io.conddo.studio.common;

/**
 * §22.3 — the manifest's {@code job.id} doesn't match the URL's {@code :id}.
 * Mapped to {@code 422 JOB_MISMATCH}.
 */
public class JobMismatchException extends RuntimeException {
    public JobMismatchException() {
        super("Bundle was exported from a different job");
    }
}
