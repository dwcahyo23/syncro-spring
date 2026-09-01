package com.syncro.org.infrastructure.db;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code user_job_bindings} (blueprint A9, story 15-2). */
public interface UserJobBindingRepository extends JpaRepository<UserJobBindingEntity, UUID> {
}
