package io.conddo.studio.storage;

/** An object-storage operation failed (upload/delete). Mapped to 502 by the API. */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
