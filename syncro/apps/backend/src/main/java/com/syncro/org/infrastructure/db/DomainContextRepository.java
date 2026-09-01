package com.syncro.org.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code domain_contexts} (blueprint A8, story 15-2). */
public interface DomainContextRepository extends JpaRepository<DomainContextEntity, UUID> {

  Optional<DomainContextEntity> findByCode(String code);
}
