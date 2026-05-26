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
 * MediaService logic with a mocked storage + repo: uploads land under the tenant
 * and store the provider's public URL, bad inputs are rejected before touching
 * storage, and delete removes both the object and the index row.
 */
class MediaServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final MediaAssetRepository repository = mock(MediaAssetRepository.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final TenantSession tenantSession = mock(TenantSession.class);
    private final MediaService service =
            new MediaService(repository, storage, tenantSession, 10L * 1024 * 1024);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private InputStream bytes() {
        return new ByteArrayInputStream(new byte[]{1, 2, 3, 4});
    }

    @Test
    void uploadStoresUnderTenantAndReturnsTheProvidersPublicUrl() {
        TenantContext.set(tenantId);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.put(anyString(), eq("image/png"), eq(4L), any()))
                .thenReturn(new ObjectStorage.Stored("cloud-public-id", "https://res.cloudinary.com/logo.png"));

        MediaService.MediaView view = service.upload("My Logo!.png", "image/png", 4, bytes(), "logo");

        assertEquals("https://res.cloudinary.com/logo.png", view.url());
        assertEquals("image/png", view.contentType());
        assertEquals("logo", view.kind());
        assertEquals("My Logo!.png", view.originalName());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).put(key.capture(), eq("image/png"), eq(4L), any());
        assertTrue(key.getValue().startsWith("tenants/" + tenantId + "/"), "key is tenant-scoped: " + key.getValue());
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
        MediaAsset asset = new MediaAsset(tenantId, "cloud-public-id",
                "https://res.cloudinary.com/logo.png", "image/png", 4, "logo.png", "logo");
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(asset));

        service.delete(id);

        verify(storage).delete("cloud-public-id");
        verify(repository).delete(asset);
    }
}
