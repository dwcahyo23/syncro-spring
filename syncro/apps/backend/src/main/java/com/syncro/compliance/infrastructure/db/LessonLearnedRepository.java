package com.syncro.compliance.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code lesson_learned} (blueprint H6, story 15-2). */
public interface LessonLearnedRepository extends JpaRepository<LessonLearnedEntity, UUID> {

  Optional<LessonLearnedEntity> findByProjectId(String projectId);
}
