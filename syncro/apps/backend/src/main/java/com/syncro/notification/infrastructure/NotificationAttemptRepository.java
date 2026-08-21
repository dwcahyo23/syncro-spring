package com.syncro.notification.infrastructure;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationAttemptRepository
    extends JpaRepository<NotificationAttemptEntity, UUID> {

  List<NotificationAttemptEntity> findByJobIdOrderByAttemptNumberAsc(UUID jobId);

  List<NotificationAttemptEntity> findByJobIdInOrderByJobIdAscAttemptNumberAsc(
      Collection<UUID> jobIds);

  long countByStatusAndAttemptedAtAfter(String status, Instant attemptedAt);

  /**
   * Most recent attempt for the status, tie-broken by id so simultaneous timestamps (bulk retry
   * rounds) resolve deterministically instead of flapping between rows across polls.
   */
  Optional<NotificationAttemptEntity> findTopByStatusAndAttemptedAtAfterOrderByAttemptedAtDescIdDesc(
      String status, Instant attemptedAt);
}
