package com.syncro.maintenance.infrastructure.db;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkOrderIdSequenceRepository extends JpaRepository<WorkOrderIdSequenceEntity, String> {

  /**
   * Race-safe row bootstrap: inserts a fresh prefix row (last_seq=0) when absent.
   * {@code ON CONFLICT DO NOTHING} makes concurrent first-calls on a new month safe
   * (a blocked insert simply does nothing once the winner commits), and guarantees
   * the {@code lockByPrefix} SELECT below always finds a row to lock.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(value = "INSERT INTO workorder_id_sequences (prefix, last_seq, updated_at) "
      + "VALUES (:prefix, 0, now()) ON CONFLICT (prefix) DO NOTHING", nativeQuery = true)
  int insertIfAbsent(@Param("prefix") String prefix);

  /** SELECT ... FOR UPDATE on the prefix row, held until the surrounding transaction commits. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from WorkOrderIdSequenceEntity s where s.prefix = :prefix")
  Optional<WorkOrderIdSequenceEntity> lockByPrefix(@Param("prefix") String prefix);
}
