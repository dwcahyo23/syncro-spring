package com.syncro.auth.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@code signature_uses} (blueprint I1, story 15-1). One workorder
 * signature per subject enforced by {@code uq_signature_uses_work_order} — a duplicate
 * approve hits the unique constraint and surfaces as a 409 conflict.
 */
public interface SignatureUseRepository extends JpaRepository<SignatureUseEntity, UUID> {

  Optional<SignatureUseEntity> findBySubjectTypeAndSubjectId(String subjectType, String subjectId);

  boolean existsBySubjectTypeAndSubjectId(String subjectType, String subjectId);
}
