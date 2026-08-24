package com.syncro.sparepart.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SparepartPriceEntryRepository extends JpaRepository<SparepartPriceEntryEntity, UUID> {
  List<SparepartPriceEntryEntity> findAllBySparepartIdOrderByEnteredAtDesc(UUID sparepartId);
}
