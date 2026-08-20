package com.syncro.notification.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationAttemptRepository
    extends JpaRepository<NotificationAttemptEntity, UUID> {

  List<NotificationAttemptEntity> findByJobIdOrderByAttemptNumberAsc(UUID jobId);

  List<NotificationAttemptEntity> findByJobIdInOrderByJobIdAscAttemptNumberAsc(
      Collection<UUID> jobIds);
}
