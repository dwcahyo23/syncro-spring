package com.syncro.auth.infrastructure;

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
}
