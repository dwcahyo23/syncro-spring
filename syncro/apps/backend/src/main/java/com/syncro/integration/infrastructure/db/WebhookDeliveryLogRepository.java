package com.syncro.integration.infrastructure.db;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code webhook_delivery_logs} (blueprint I4, story 15-2). */
public interface WebhookDeliveryLogRepository extends JpaRepository<WebhookDeliveryLogEntity, UUID> {
}
