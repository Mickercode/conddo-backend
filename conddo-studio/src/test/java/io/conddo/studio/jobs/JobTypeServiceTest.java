package io.conddo.studio.jobs;

import io.conddo.studio.common.ConflictException;
import io.conddo.studio.common.NotFoundException;
import io.conddo.studio.domain.JobType;
import io.conddo.studio.repository.JobTypeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JobTypeService: id format is enforced, slaHours must be positive, create
 * collides on duplicate, update is genuinely partial, disable soft-deletes.
 */
class JobTypeServiceTest {

    private final JobTypeRepository repository = mock(JobTypeRepository.class);
    private final JobTypeService service = new JobTypeService(repository);

    @Test
    void createPersistsAndReturnsTheType() {
        when(repository.existsById("SOCIAL_MEDIA")).thenReturn(false);
        when(repository.save(any(JobType.class))).thenAnswer(inv -> inv.getArgument(0));

        JobType saved = service.create("SOCIAL_MEDIA", "Social Media", "#22C55E",
                List.of("DESIGNER", "WRITER"), 12, true, List.of(Map.of("id", "brand", "label", "Brand colours")));

        assertEquals("SOCIAL_MEDIA", saved.getId());
        assertEquals("Social Media", saved.getDisplayName());
        assertEquals(12, saved.getSlaHours());
        assertTrue(saved.isQaRequired());
        verify(repository).save(any(JobType.class));
    }

    @Test
    void createRejectsLowerCaseId() {
        assertThrows(IllegalArgumentException.class, () -> service.create(
                "social_media", "Social Media", null, List.of(), 12, true, null));
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsTooShortId() {
        assertThrows(IllegalArgumentException.class, () -> service.create(
                "XX", "Short", null, List.of(), 12, true, null));
    }

    @Test
    void createRejectsBlankDisplayName() {
        assertThrows(IllegalArgumentException.class, () -> service.create(
                "SOCIAL_MEDIA", "  ", null, List.of(), 12, true, null));
    }

    @Test
    void createRejectsZeroOrNegativeSla() {
        assertThrows(IllegalArgumentException.class, () -> service.create(
                "SOCIAL_MEDIA", "Social", null, List.of(), 0, true, null));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                "SOCIAL_MEDIA", "Social", null, List.of(), -5, true, null));
    }

    @Test
    void createConflictsOnDuplicateId() {
        when(repository.existsById("WEBSITE_BUILD")).thenReturn(true);
        assertThrows(ConflictException.class, () -> service.create(
                "WEBSITE_BUILD", "Already there", null, List.of(), 24, true, null));
    }

    @Test
    void updateIsPartialAndOnlyTouchesNonNullFields() {
        JobType existing = existing("WEBSITE_BUILD", "Website Build", 48, true, true);
        when(repository.findById("WEBSITE_BUILD")).thenReturn(Optional.of(existing));
        when(repository.save(any(JobType.class))).thenAnswer(inv -> inv.getArgument(0));

        // Only slaHours + qaRequired in this update — display name + active stay.
        JobType updated = service.update("WEBSITE_BUILD", null, null, null, 36, false, null, null);

        assertEquals("Website Build", updated.getDisplayName());
        assertEquals(36, updated.getSlaHours());
        assertFalse(updated.isQaRequired());
        assertTrue(updated.isActive());   // not touched
    }

    @Test
    void updateRejectsNegativeSla() {
        JobType existing = existing("WEBSITE_BUILD", "Website Build", 48, true, true);
        when(repository.findById("WEBSITE_BUILD")).thenReturn(Optional.of(existing));
        assertThrows(IllegalArgumentException.class, () -> service.update(
                "WEBSITE_BUILD", null, null, null, -1, null, null, null));
    }

    @Test
    void updateOnUnknownTypeIs404() {
        when(repository.findById("MYSTERY")).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.update(
                "MYSTERY", "Anything", null, null, null, null, null, null));
    }

    @Test
    void disableSoftDeletes() {
        JobType existing = existing("WEBSITE_BUILD", "Website Build", 48, true, true);
        when(repository.findById("WEBSITE_BUILD")).thenReturn(Optional.of(existing));
        when(repository.save(any(JobType.class))).thenAnswer(inv -> inv.getArgument(0));

        service.disable("WEBSITE_BUILD");

        assertFalse(existing.isActive());
        verify(repository).save(existing);
    }

    @Test
    void disableOnUnknownTypeIs404() {
        when(repository.findById("MYSTERY")).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.disable("MYSTERY"));
    }

    // ----- helpers ------------------------------------------------------------

    private static JobType existing(String id, String label, int sla, boolean qa, boolean active) {
        JobType type = new JobType(id, label, "#7C5CBF", List.of("DEVELOPER"), sla, qa, List.of());
        type.setActive(active);
        return type;
    }
}
