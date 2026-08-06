package com.syncro.machine.infrastructure;

import jakarta.persistence.*;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Transient;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.auth.infrastructure.AuthUserEntity;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "machine_responsibility",
       uniqueConstraints = @UniqueConstraint(columnNames = {"machine_id", "user_id", "responsibility_level"}))
public class MachineResponsibilityEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "machine_id", nullable = false)
    private UUID machineId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "responsibility_level", nullable = false)
    @Enumerated(EnumType.STRING)
    private ResponsibilityLevel responsibilityLevel;

    @Column(name = "plant_id", nullable = false)
    private UUID plantId;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE DEFAULT now()")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE DEFAULT now()")
    private Instant updatedAt;

    public MachineResponsibilityEntity() {
    }

    // Constructor used by service when passing entities
    public MachineResponsibilityEntity(UUID id, MachineEntity machine, AuthUserEntity user, ResponsibilityLevel level, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.machineId = machine.getId();
        this.userId = user.getId();
        this.responsibilityLevel = level;
        this.plantId = machine.getPlant().getId();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.machine = machine;
        this.user = user;
    }

    // Existing constructor for direct UUID usage
    public MachineResponsibilityEntity(UUID id, UUID machineId, UUID userId, ResponsibilityLevel responsibilityLevel, UUID plantId, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.machineId = machineId;
        this.userId = userId;
        this.responsibilityLevel = responsibilityLevel;
        this.plantId = plantId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void update(ResponsibilityLevel responsibilityLevel, Instant updatedAt) {
        this.responsibilityLevel = responsibilityLevel;
        this.updatedAt = updatedAt;
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getMachineId() { return machineId; }
    public UUID getUserId() { return userId; }
    public ResponsibilityLevel getResponsibilityLevel() { return responsibilityLevel; }
    public ResponsibilityLevel getLevel() { return responsibilityLevel; }
    public UUID getPlantId() { return plantId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // JPA relationships (read‑only) for convenience
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "machine_id", insertable = false, updatable = false)
    private MachineEntity machine;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private AuthUserEntity user;

    public MachineEntity getMachine() { return machine; }
    public void setMachine(MachineEntity machine) { this.machine = machine; }
    public AuthUserEntity getUser() { return user; }
    public void setUser(AuthUserEntity user) { this.user = user; }

    // Enum for responsibility levels

}
