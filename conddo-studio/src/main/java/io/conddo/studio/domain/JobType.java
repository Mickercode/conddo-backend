package io.conddo.studio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;

/** A category of Studio job (Website Build, Graphic Design, …) — seeded reference data. */
@Entity
@Table(name = "job_types")
public class JobType {

    @Id
    private String id;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String colour;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "assigned_to_roles", nullable = false)
    private List<String> assignedToRoles;

    @Column(name = "sla_hours", nullable = false)
    private int slaHours;

    @Column(name = "qa_required", nullable = false)
    private boolean qaRequired;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "qa_checklist", nullable = false)
    private List<Map<String, Object>> qaChecklist;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected JobType() {
    }

    /**
     * Admin-facing constructor. Used by {@link io.conddo.studio.jobs.JobTypeService}
     * to create a fresh job type from CRUD; the no-arg constructor remains JPA's
     * default for hydration.
     */
    public JobType(String id, String displayName, String colour, List<String> assignedToRoles,
                   int slaHours, boolean qaRequired, List<Map<String, Object>> qaChecklist) {
        this.id = id;
        this.displayName = displayName;
        this.colour = colour == null || colour.isBlank() ? "#7C5CBF" : colour;
        this.assignedToRoles = assignedToRoles == null ? List.of() : assignedToRoles;
        this.slaHours = slaHours;
        this.qaRequired = qaRequired;
        this.qaChecklist = qaChecklist == null ? List.of() : qaChecklist;
    }

    // ----- mutators (admin CRUD) ---------------------------------------------

    public void rename(String displayName) {
        if (displayName != null && !displayName.isBlank()) {
            this.displayName = displayName;
        }
    }

    public void recolour(String colour) {
        if (colour != null && !colour.isBlank()) {
            this.colour = colour;
        }
    }

    public void setSlaHours(int slaHours) {
        if (slaHours > 0) {
            this.slaHours = slaHours;
        }
    }

    public void setQaRequired(boolean qaRequired) {
        this.qaRequired = qaRequired;
    }

    public void setAssignedToRoles(List<String> assignedToRoles) {
        if (assignedToRoles != null) {
            this.assignedToRoles = assignedToRoles;
        }
    }

    public void setQaChecklist(List<Map<String, Object>> qaChecklist) {
        if (qaChecklist != null) {
            this.qaChecklist = qaChecklist;
        }
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getColour() {
        return colour;
    }

    public List<String> getAssignedToRoles() {
        return assignedToRoles;
    }

    public int getSlaHours() {
        return slaHours;
    }

    public boolean isQaRequired() {
        return qaRequired;
    }

    public List<Map<String, Object>> getQaChecklist() {
        return qaChecklist;
    }

    public boolean isActive() {
        return active;
    }
}
