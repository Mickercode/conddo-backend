package io.conddo.api.storage;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import io.conddo.core.storage.ObjectStorage;
import io.conddo.core.storage.StorageException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Map;

/**
 * {@link ObjectStorage} backed by Cloudinary. Upload returns a permanent, public,
 * CDN-backed {@code secure_url} (embedded in the dashboard, public website, and
 * emails — no presigning, no expiry) and the {@code public_id} we keep as the
 * delete handle.
 *
 * <p>Configured by {@code CLOUDINARY_URL} ({@code cloudinary://<key>:<secret>@<cloud>}).
 * Connectionless at startup, so a missing/invalid config fails the upload with a
 * clear 502 rather than crashing the app. Our allowlist (images + PDF) are all
 * Cloudinary {@code image} resources, so upload/delete both use that type.
 */
@Component
public class CloudinaryObjectStorage implements ObjectStorage {

    private static final String RESOURCE_TYPE = "image";   // images + PDF are 'image' resources

    private final Cloudinary cloudinary;   // null when CLOUDINARY_URL is unset

    public CloudinaryObjectStorage(@Value("${conddo.cloudinary.url:}") String cloudinaryUrl) {
        this.cloudinary = (cloudinaryUrl == null || cloudinaryUrl.isBlank()) ? null : new Cloudinary(cloudinaryUrl);
    }

    @Override
    public Stored put(String key, String contentType, long size, InputStream data) {
        Cloudinary client = require();
        try {
            Map<?, ?> result = client.uploader().upload(data.readAllBytes(), ObjectUtils.asMap(
                    "public_id", key,
                    "resource_type", RESOURCE_TYPE,
                    "overwrite", true));
            return new Stored((String) result.get("public_id"), (String) result.get("secure_url"));
        } catch (Exception ex) {
            throw new StorageException("Cloudinary upload failed for " + key, ex);
        }
    }

    @Override
    public void delete(String id) {
        Cloudinary client = require();
        try {
            client.uploader().destroy(id, ObjectUtils.asMap("resource_type", RESOURCE_TYPE, "invalidate", true));
        } catch (Exception ex) {
            throw new StorageException("Cloudinary delete failed for " + id, ex);
        }
    }

    private Cloudinary require() {
        if (cloudinary == null) {
            throw new StorageException("Cloudinary is not configured — set CLOUDINARY_URL", null);
        }
        return cloudinary;
    }
}
