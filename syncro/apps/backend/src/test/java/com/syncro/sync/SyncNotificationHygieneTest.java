package com.syncro.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notification hygiene (AD-7/AD-8, story 13-3): the sync module must never write
 * {@code notification_jobs} directly. This test verifies that no class in the
 * sync module's main source imports from {@code com.syncro.notification.infrastructure}
 * or {@code com.syncro.notification.application}.
 *
 * <p>The sync module never calls into the notification module's worker/outbox
 * infrastructure — notifications flow only through
 * {@code WorkorderImportService.upsert()} in the maintenance module.
 */
class SyncNotificationHygieneTest {

  private static final String FORBIDDEN_APPLICATION =
      "com.syncro.notification.application";
  private static final String FORBIDDEN_INFRASTRUCTURE =
      "com.syncro.notification.infrastructure";

  @Test
  @DisplayName("13.3-HYG-001 P0 no sync module class imports notification infrastructure or application")
  void noSyncClassImportsNotification() throws IOException {
    var violations = new ArrayList<String>();

    try (var files = Files.walk(syncMainSource())) {
      for (var file : (Iterable<Path>) files::iterator) {
        if (!file.toString().endsWith(".java")) {
          continue;
        }
        var content = Files.readString(file);
        if (imports(content, FORBIDDEN_APPLICATION)
            || imports(content, FORBIDDEN_INFRASTRUCTURE)) {
          violations.add(file.toString());
        }
      }
    }

    assertThat(violations)
        .as("Sync module classes must not import com.syncro.notification.infrastructure "
            + "or com.syncro.notification.application (AD-7/AD-8)")
        .isEmpty();
  }

  private static boolean imports(String content, String forbiddenPackage) {
    return content.contains("import " + forbiddenPackage + ".")
        || content.contains("import " + forbiddenPackage + ";");
  }

  /**
   * Locates the sync module's main source. Maven runs tests with the module directory
   * as basedir, but some IDEs start from the repository root — try both.
   */
  private static Path syncMainSource() {
    var fromRoot = Paths.get("syncro/apps/backend/src/main/java/com/syncro/sync");
    if (Files.isDirectory(fromRoot)) {
      return fromRoot;
    }
    return Paths.get("src/main/java/com/syncro/sync");
  }
}