package com.syncro.sparepart.request.infrastructure.db;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SparepartRequestRepository extends JpaRepository<SparepartRequestEntity, UUID> {

  List<SparepartRequestEntity> findByWorkOrderIdOrderByRequestedAtAsc(String workOrderId);

  Optional<SparepartRequestEntity> findById(UUID id);
}
