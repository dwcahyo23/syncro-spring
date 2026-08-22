package com.syncro.notification.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WahaTemplateRepository extends JpaRepository<WahaTemplateEntity, UUID> {

  Optional<WahaTemplateEntity> findByTemplateKey(String templateKey);

  @Modifying(clearAutomatically = true)
  @Query(value = """
      INSERT INTO waha_templates (id, template_key, body, created_at, updated_at)
      VALUES (:id, :templateKey, :body, :now, :now)
      ON CONFLICT (template_key) DO UPDATE SET body = :body, updated_at = :now
      """, nativeQuery = true)
  void upsert(@Param("id") UUID id, @Param("templateKey") String templateKey,
      @Param("body") String body, @Param("now") Instant now);
}
