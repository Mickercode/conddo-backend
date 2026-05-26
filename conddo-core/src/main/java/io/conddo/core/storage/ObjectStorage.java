package io.conddo.core.storage;

import java.io.InputStream;
import java.time.Duration;

/**
 * Port for S3-compatible object storage (MinIO self-hosted, or a cloud bucket —
 * Cloudflare R2 / AWS S3 / Backblaze B2). Implemented in {@code conddo-api} with
 * the MinIO SDK, which speaks the S3 API to any of them. Keeps the SDK out of
 * {@code conddo-core}.
 *
 * <p>Serving is via {@link #presignedGetUrl}: the API hands the browser a
 * short-lived signed URL and the bucket serves the bytes directly, so the bucket
 * stays private and the API never proxies large files. Transport failures throw
 * {@link StorageException} (mapped to 502 by the API).
 */
public interface ObjectStorage {

    /** Stores an object at {@code key}; overwrites if it exists. */
    void put(String key, String contentType, long size, InputStream data);

    /** A time-limited signed URL the browser can GET directly (bucket stays private). */
    String presignedGetUrl(String key, Duration ttl);

    /** Removes the object; a no-op if it's already gone. */
    void delete(String key);
}
