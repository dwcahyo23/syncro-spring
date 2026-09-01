package com.syncro.notification.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code whatsapp_message_logs} (blueprint I5, story 15-2). */
public interface WhatsAppMessageLogRepository extends JpaRepository<WhatsAppMessageLogEntity, UUID> {

  Optional<WhatsAppMessageLogEntity> findByWahaMessageId(String wahaMessageId);
}
