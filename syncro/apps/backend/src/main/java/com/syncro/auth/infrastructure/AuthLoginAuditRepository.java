package com.syncro.auth.infrastructure;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code auth_login_audits} (blueprint I2, story 15-2). */
public interface AuthLoginAuditRepository extends JpaRepository<AuthLoginAuditEntity, UUID> {
}
