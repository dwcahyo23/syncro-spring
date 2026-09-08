package com.syncro.integration.infrastructure.db;

import com.syncro.integration.domain.WebhookDirection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code webhook_configs} (blueprint I4, story 15-2; CRUD in 22-1). */
public interface WebhookConfigRepository extends JpaRepository<WebhookConfigEntity, UUID> {

  Optional<WebhookConfigEntity> findByName(String name);

  /** Event-matching source for the dispatch listener (story 22-1, AC1). */
  List<WebhookConfigEntity> findByDirectionAndActiveTrue(WebhookDirection direction);
}
