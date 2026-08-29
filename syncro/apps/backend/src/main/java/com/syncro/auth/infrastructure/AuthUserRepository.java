package com.syncro.auth.infrastructure;

import com.syncro.auth.domain.ApplicationRole;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthUserRepository extends JpaRepository<AuthUserEntity, UUID> {
  Optional<AuthUserEntity> findByLoginIdentifierIgnoreCase(String loginIdentifier);

  @Query("""
      select u from AuthUserEntity u
      where u.nik is not null and lower(u.nik) = lower(:nik)
      """)
  Optional<AuthUserEntity> findByNikIgnoreCase(@Param("nik") String nik);

  @Query("""
      select u from AuthUserEntity u
      where u.phoneNumber is not null and u.phoneNumber = :phoneNumber
      """)
  Optional<AuthUserEntity> findByPhoneNumber(@Param("phoneNumber") String phoneNumber);

  List<AuthUserEntity> findAllByOrderByLoginIdentifierAsc();

  /**
   * Story 12-3 escalation recipients: users holding one of the given application roles
   * (INVENTORY_MAINTENANCE/STOREKEEPER) with a non-blank whatsapp number.
   */
  @Query("""
      select u from AuthUserEntity u
      where u.applicationRole in :roles
        and u.whatsappNumber is not null
        and trim(u.whatsappNumber) <> ''
      """)
  List<AuthUserEntity> findAllByApplicationRoleInWithWhatsapp(
      @Param("roles") Collection<ApplicationRole> roles);

  /**
   * Batch user lookup by ids (story 14-2, FR-174): resolves technician display names
   * for the technician KPI dashboard. One query, no N+1.
   */
  @Query("select u from AuthUserEntity u where u.id in :ids")
  List<AuthUserEntity> findByIds(@Param("ids") Collection<UUID> ids);
}
