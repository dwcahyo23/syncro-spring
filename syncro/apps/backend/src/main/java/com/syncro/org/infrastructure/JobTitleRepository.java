package com.syncro.org.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobTitleRepository extends JpaRepository<JobTitleEntity, UUID> {

  List<JobTitleEntity> findAllByOrderByNameAsc();

  Optional<JobTitleEntity> findByCodeIgnoreCase(String code);

  boolean existsByCodeIgnoreCase(String code);
}
