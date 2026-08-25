package com.syncro.org.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamMemberRepository extends JpaRepository<TeamMemberEntity, TeamMemberId> {

  @Query("""
      select m from TeamMemberEntity m
      join fetch m.user u
      where m.id.teamId = :teamId
      order by u.loginIdentifier asc
      """)
  List<TeamMemberEntity> findAllByTeamId(@Param("teamId") UUID teamId);

  boolean existsByIdTeamIdAndIdUserId(UUID teamId, UUID userId);

  long countByIdTeamId(UUID teamId);

  void deleteByIdTeamIdAndIdUserId(UUID teamId, UUID userId);

  void deleteByIdTeamId(UUID teamId);
}
