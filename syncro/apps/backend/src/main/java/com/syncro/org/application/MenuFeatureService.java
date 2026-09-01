package com.syncro.org.application;

import com.syncro.org.infrastructure.db.MenuFeatureEntity;
import com.syncro.org.infrastructure.db.MenuFeatureRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only access to menu features (blueprint A7). */
@Service
public class MenuFeatureService {

  private final MenuFeatureRepository menuFeatures;

  public MenuFeatureService(MenuFeatureRepository menuFeatures) {
    this.menuFeatures = menuFeatures;
  }

  @Transactional(readOnly = true)
  public MenuFeatureListView list() {
    return new MenuFeatureListView(
        menuFeatures.findAll().stream().map(this::toView).toList());
  }

  private MenuFeatureView toView(MenuFeatureEntity feature) {
    return new MenuFeatureView(feature.getId(), feature.getCode(), feature.getModule(), feature.getName(),
        feature.isActive());
  }

  public record MenuFeatureView(UUID id, String code, String module, String name, boolean active) {
  }

  public record MenuFeatureListView(List<MenuFeatureView> items) {
  }
}