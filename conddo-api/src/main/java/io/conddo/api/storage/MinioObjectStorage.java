package io.conddo.api.storage;

import io.conddo.api.config.MinioProperties;
import io.conddo.core.storage.ObjectStorage;
import io.conddo.core.storage.StorageException;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Duration;

/**
 * {@link ObjectStorage} over the S3 API via the MinIO SDK — works against
 * self-hosted MinIO or any S3-compatible cloud bucket (Cloudflare R2, AWS S3,
 * Backblaze B2). Connectionless at startup (the {@link MinioClient} bean only
 * talks to the store on use), so a missing/unreachable bucket fails the upload
 * with a clear 502 rather than crashing the app.
 *
 * <p>The bucket {@code endpoint} must be reachable by the <b>browser</b>, since
 * presigned URLs point at it directly (use the public host, not an internal one).
 */
@Component
public class MinioObjectStorage implements ObjectStorage {

    private static final int MAX_EXPIRY_SECONDS = 7 * 24 * 60 * 60;   // S3 presign ceiling

    private final MinioClient client;
    private final String bucket;
    private volatile boolean bucketReady;

    public MinioObjectStorage(MinioClient client, MinioProperties properties) {
        this.client = client;
        this.bucket = properties.bucket();
    }

    @Override
    public void put(String key, String contentType, long size, InputStream data) {
        ensureBucket();
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket).object(key)
                    .stream(data, size, -1)
                    .contentType(contentType == null ? "application/octet-stream" : contentType)
                    .build());
        } catch (Exception ex) {
            throw new StorageException("Failed to store object " + key, ex);
        }
    }

    @Override
    public String presignedGetUrl(String key, Duration ttl) {
        int seconds = (int) Math.max(1, Math.min(ttl.toSeconds(), MAX_EXPIRY_SECONDS));
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket(bucket).object(key).expiry(seconds).build());
        } catch (Exception ex) {
            throw new StorageException("Failed to sign URL for " + key, ex);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception ex) {
            throw new StorageException("Failed to delete object " + key, ex);
        }
    }

    /** Create the bucket on first use if it doesn't exist (no-op once confirmed). */
    private void ensureBucket() {
        if (bucketReady) {
            return;
        }
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            bucketReady = true;
        } catch (Exception ex) {
            throw new StorageException("Object storage bucket '" + bucket + "' is unavailable", ex);
        }
    }
}
