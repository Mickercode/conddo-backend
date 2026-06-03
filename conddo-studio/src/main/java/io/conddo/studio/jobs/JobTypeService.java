package io.conddo.studio.jobs;

import io.conddo.studio.common.ConflictException;
import io.conddo.studio.common.NotFoundException;
import io.conddo.studio.domain.JobType;
import io.conddo.studio.repository.JobTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Admin CRUD over {@link JobType} (Infrastructure §8 — production catalogue).
 * Lets ADMINs add new categories (e.g. {@code SOCIAL_MEDIA}), tune per-type SLA
 * hours (the SLA-settings half of the spec), and retire types via soft-delete
 * (the {@code is_active} flag — referenced jobs keep their historical record).
 *
 * <p>The service intentionally rejects hard-deletes: every {@code studio.jobs}
 * row holds an FK to {@code studio.job_types(id)}, so dropping a type would
 * orphan historical work. {@link #disable(String)} flips {@code is_active}
 * which removes the type from the create-job dropdown without touching past
 * activity.
 */
@Service
public class JobTypeService {

    private static final java.util.regex.Pattern ID_PATTERN =
            java.util.regex.Pattern.compile("^[A-Z][A-Z0-9_]{2,31}$");

    private final JobTypeRepository repository;

    public JobTypeService(JobTypeRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<JobType> list() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public JobType get(String id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Job type not found: " + id));
    }

    /**
     * Create a new job type. The id is a stable UPPER_SNAKE_CASE token used in
     * URLs, job numbers (the prefix lookup), and skill matching — picked by the
     * admin once and never changed.
     */
    @Transactional
    public JobType create(String id, String displayName, String colour, List<String> assignedToRoles,
                          int slaHours, boolean qaRequired, List<Map<String, Object>> qaChecklist) {
        validateId(id);
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName is required");
        }
        if (slaHours <= 0) {
            throw new IllegalArgumentException("slaHours must be positive");
        }
        if (repository.existsById(id)) {
            throw new ConflictException("Job type already exists: " + id);
        }
        return repository.save(new JobType(id, displayName, colour, assignedToRoles, slaHours,
                qaRequired, qaChecklist));
    }

    /**
     * Update mutable fields. Any null/blank value is left untouched — the admin
     * UI sends partial bodies (PATCH semantics). {@code id} can never change;
     * to "rename" the slug, retire one type and create another.
     */
    @Transactional
    public JobType update(String id, String displayName, String colour, List<String> assignedToRoles,
                          Integer slaHours, Boolean qaRequired, List<Map<String, Object>> qaChecklist,
                          Boolean active) {
        JobType type = get(id);
        type.rename(displayName);
        type.recolour(colour);
        if (assignedToRoles != null) {
            type.setAssignedToRoles(assignedToRoles);
        }
        if (slaHours != null) {
            if (slaHours <= 0) {
                throw new IllegalArgumentException("slaHours must be positive");
            }
            type.setSlaHours(slaHours);
        }
        if (qaRequired != null) {
            type.setQaRequired(qaRequired);
        }
        if (qaChecklist != null) {
            type.setQaChecklist(qaChecklist);
        }
        if (active != null) {
            type.setActive(active);
        }
        return repository.save(type);
    }

    /** Soft-delete — flips {@code is_active} so the type stops appearing on the create dropdown. */
    @Transactional
    public void disable(String id) {
        JobType type = get(id);
        type.setActive(false);
        repository.save(type);
    }

    private static void validateId(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "id must be UPPER_SNAKE_CASE (3-32 chars, starting with a letter), got: " + id);
        }
    }
}
