package com.syncro.auth.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthUserPlantAssignmentRepository
    extends JpaRepository<AuthUserPlantAssignmentEntity, AuthUserPlantAssignmentId> {
  List<AuthUserPlantAssignmentEntity> findByAuthUserId(UUID authUserId);
}
