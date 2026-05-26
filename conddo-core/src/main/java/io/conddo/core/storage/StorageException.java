package io.conddo.core.storage;

/** An object-storage operation failed (upload/presign/delete). Mapped to 502 by the API. */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
