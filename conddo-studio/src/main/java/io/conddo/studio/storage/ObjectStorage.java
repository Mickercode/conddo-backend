package io.conddo.studio.storage;

import java.io.InputStream;

/**
 * Port for asset object storage. Implemented in this service with Cloudinary
 * (CDN-backed; upload returns a permanent, public {@code secure_url}). Mockable
 * in tests. Mirrors the platform's {@code conddo-core/storage/ObjectStorage}
 * shape — Studio is standalone (no conddo-core dep), so we keep our own copy.
 */
public interface ObjectStorage {

    /** Uploads bytes under a suggested {@code key}; returns the provider id + public URL. */
    Stored put(String key, String contentType, long size, InputStream data);

    /** Removes the object by its provider id; a no-op if it's already gone. */
    void delete(String id);

    /** A stored object: the provider id (delete handle) and its public URL. */
    record Stored(String id, String url) {
    }
}
