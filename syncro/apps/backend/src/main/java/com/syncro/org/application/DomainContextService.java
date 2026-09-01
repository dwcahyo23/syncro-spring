package com.syncro.org.application;

import com.syncro.org.infrastructure.db.DomainContextEntity;
import com.syncro.org.infrastructure.db.DomainContextRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only access to domain contexts (blueprint A8). */
@Service
public class DomainContextService {

  private final DomainContextRepository domainContexts;

  public DomainContextService(DomainContextRepository domainContexts) {
    this.domainContexts = domainContexts;
  }

  @Transactional(readOnly = true)
  public DomainContextListView list() {
    return new DomainContextListView(
        domainContexts.findAll().stream().map(this::toView).toList());
  }

  private DomainContextView toView(DomainContextEntity domain) {
    return new DomainContextView(domain.getId(), domain.getCode(), domain.getName());
  }

  public record DomainContextView(UUID id, String code, String name) {
  }

  public record DomainContextListView(List<DomainContextView> items) {
  }
}