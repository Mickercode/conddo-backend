package io.conddo.core.storage;

import java.io.InputStream;

/**
 * Port for media object storage. Implemented in {@code conddo-api} with Cloudinary
 * (CDN-backed; upload returns a permanent, public {@code secure_url}). Keeps the
 * provider SDK out of {@code conddo-core}.
 *
 * <p>{@link #put} returns the stored object's provider id (for later delete) and a
 * publicly-fetchable URL — stored on the media row and embedded directly in the
 * dashboard, the public website, and emails (so it must not expire). Transport
 * failures throw {@link StorageException} (mapped to 502 by the API).
 */
public interface ObjectStorage {

    /** Uploads bytes under a suggested {@code key}; returns the stored id + public URL. */
    Stored put(String key, String contentType, long size, InputStream data);

    /** Removes the object by its provider id; a no-op if it's already gone. */
    void delete(String id);

    /** A stored object: the provider id (delete handle) and its public URL. */
    record Stored(String id, String url) {
    }
}
