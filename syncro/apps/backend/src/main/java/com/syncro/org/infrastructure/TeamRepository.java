package com.syncro.org.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamRepository extends JpaRepository<TeamEntity, UUID> {

  List<TeamEntity> findAllByOrderByNameAsc();

  Optional<TeamEntity> findByNameIgnoreCase(String name);

  boolean existsByNameIgnoreCase(String name);

  /**
   * Machine ids targeted by the user's active (non-expired) teams. Expiry is
   * evaluated lazily per derive against the injected {@code now} — no scheduled
   * job and no stored {@code active} column (AD-13).
   */
  @Query(value = """
      select tm.machine_id
      from team_members tmm
      join teams t on t.id = tmm.team_id
      join team_machines tm on tm.team_id = t.id
      where tmm.user_id = :userId
        and t.expires_at > :now
      """, nativeQuery = true)
  Set<UUID> findActiveTeamMachineIdsByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
