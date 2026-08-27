package com.syncro.org.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DepartmentMemberRepository extends JpaRepository<DepartmentMemberEntity, UUID> {

  @Query("""
      select m from DepartmentMemberEntity m
      where m.departmentId = :departmentId
      order by m.userId asc
      """)
  List<DepartmentMemberEntity> findByDepartmentId(@Param("departmentId") UUID departmentId);

  long countByDepartmentId(UUID departmentId);

  void deleteByDepartmentId(UUID departmentId);
}
