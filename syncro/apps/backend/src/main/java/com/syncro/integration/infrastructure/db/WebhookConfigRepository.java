package com.syncro.integration.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code webhook_configs} (blueprint I4, story 15-2). */
public interface WebhookConfigRepository extends JpaRepository<WebhookConfigEntity, UUID> {

  Optional<WebhookConfigEntity> findByName(String name);
}
