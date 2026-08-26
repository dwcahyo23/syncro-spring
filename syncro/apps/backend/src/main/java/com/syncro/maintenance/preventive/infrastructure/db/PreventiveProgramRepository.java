package com.syncro.maintenance.preventive.infrastructure.db;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PreventiveProgramRepository extends JpaRepository<PreventiveProgramEntity, UUID> {

  List<PreventiveProgramEntity> findAllByOrderByCreatedAtDesc();
}