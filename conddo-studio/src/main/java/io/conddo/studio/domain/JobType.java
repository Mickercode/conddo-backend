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
