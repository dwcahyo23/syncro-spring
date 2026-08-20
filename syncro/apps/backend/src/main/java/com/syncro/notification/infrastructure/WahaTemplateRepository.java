package com.syncro.notification.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WahaTemplateRepository extends JpaRepository<WahaTemplateEntity, UUID> {

  Optional<WahaTemplateEntity> findByTemplateKey(String templateKey);
}
