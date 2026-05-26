package io.conddo.core.service;

import io.conddo.core.domain.MediaAsset;
import io.conddo.core.repository.MediaAssetRepository;
import io.conddo.core.storage.ObjectStorage;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MediaService logic with mocked storage + repo: uploads land under the tenant
 * (collision-free key, presigned URL returned), bad inputs are rejected before
 * touching storage, and delete removes both the object and the index row.
 */
class MediaServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final MediaAssetRepository repository = mock(MediaAssetRepository.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final TenantSession tenantSession = mock(TenantSession.class);
    // No public base URL → falls back to presigned URLs (see publicBaseUrlYieldsStableUrl).
    private final MediaService service =
            new MediaService(repository, storage, tenantSession, "", Duration.ofHours(24), 10L * 1024 * 1024);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private InputStream bytes() {
        return new ByteArrayInputStream(new byte[]{1, 2, 3, 4});
    }

    @Test
    void uploadStoresUnderTenantAndReturnsPresignedUrl() {
        TenantContext.set(tenantId);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.presignedGetUrl(anyString(), any())).thenReturn("https://bucket/signed");

        MediaService.MediaView view = service.upload("My Logo!.png", "image/png", 4, bytes(), "logo");

        assertEquals("https://bucket/signed", view.url());
        assertEquals("image/png", view.contentType());
        assertEquals("logo", view.kind());
        assertEquals("My Logo!.png", view.originalName());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).put(key.capture(), eq("image/png"), eq(4L), any());
        assertTrue(key.getValue().startsWith("tenants/" + tenantId + "/"), "key is tenant-scoped");
        assertTrue(key.getValue().endsWith("My_Logo_.png"), "filename is sanitised: " + key.getValue());
    }

    @Test
    void publicBaseUrlYieldsStableUrlNotPresigned() {
        TenantContext.set(tenantId);
        MediaService withCdn = new MediaService(
                repository, storage, tenantSession, "https://media.conddo.io/", Duration.ofHours(24), 10L * 1024 * 1024);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MediaService.MediaView view = withCdn.upload("logo.png", "image/png", 4, bytes(), "logo");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).put(key.capture(), eq("image/png"), eq(4L), any());
        assertEquals("https://media.conddo.io/" + key.getValue(), view.url(), "stable public URL");
        verify(storage, never()).presignedGetUrl(anyString(), any());
    }

    @Test
    void rejectsUnsupportedTypeBeforeStoring() {
        TenantContext.set(tenantId);
        assertThrows(IllegalArgumentException.class,
                () -> service.upload("app.exe", "application/octet-stream", 10, bytes(), null));
        verify(storage, never()).put(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void rejectsEmptyFile() {
        TenantContext.set(tenantId);
        assertThrows(IllegalArgumentException.class,
                () -> service.upload("empty.png", "image/png", 0, bytes(), null));
        verify(storage, never()).put(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void deleteRemovesObjectThenRow() {
        TenantContext.set(tenantId);
        MediaAsset asset = new MediaAsset(tenantId, "tenants/x/abc-logo.png", "image/png", 4, "logo.png", "logo");
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(asset));

        service.delete(id);

        verify(storage).delete("tenants/x/abc-logo.png");
        verify(repository).delete(asset);
    }
}
