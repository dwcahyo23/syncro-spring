package com.syncro.org.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@code department_users} (renamed from department_members by the
 * ORM target blueprint A3, story 15-1).
 */
public interface DepartmentUserRepository extends JpaRepository<DepartmentUserEntity, UUID> {

  @Query("""
      select m from DepartmentUserEntity m
      where m.departmentId = :departmentId
      order by m.userId asc
      """)
  List<DepartmentUserEntity> findByDepartmentId(@Param("departmentId") UUID departmentId);

  long countByDepartmentId(UUID departmentId);

  void deleteByDepartmentId(UUID departmentId);
}
