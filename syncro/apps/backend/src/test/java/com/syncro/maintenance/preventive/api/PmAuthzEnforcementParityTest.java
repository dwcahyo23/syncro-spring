package com.syncro.maintenance.preventive.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the OPA rollout parity for the PM surface (stories 19-4/19-5/19-6) and the
 * compliance surface (story 21-1): every path in the rego pm_work_order_paths,
 * pm_execution_paths, compliance_nc_paths and compliance_eight_d_verify_paths sets
 * must also appear in SYNCRO_AUTHZ_ENFORCED_PATHS in syncro/.env.example, or the
 * endpoint ships unenforced. The rego sets are parsed from the policy source (no
 * hand-copied list to drift). Plain file read — no Spring context; the rego suite
 * covers the policy side, this covers the env rollout side.
 */
class PmAuthzEnforcementParityTest {

  private static final Pattern SET_BODY = Pattern.compile(
      "(pm_work_order_paths|pm_execution_paths|compliance_nc_paths|compliance_eight_d_verify_paths) := \\{([^}]*)\\}");
  private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

  @Test
  @DisplayName("19.6-UNIT-001 P0 SYNCRO_AUTHZ_ENFORCED_PATHS carries every rego pm-work-orders and pm-executions path")
  void enforcedPathsCoverTheRegoPmSets() throws IOException {
    var rego = Files.readString(findRepoFile(Path.of("syncro", "authz", "policy", "authz.rego")));
    var envFile = findRepoFile(Path.of("syncro", ".env.example"));
    assertThat(envFile).exists();

    var setPaths = SET_BODY.matcher(rego).results()
        .flatMap(set -> QUOTED.matcher(set.group(2)).results())
        .map(m -> m.group(1))
        .toList();
    // Guard the parser itself: both sets must be found, and the 19-6 report path
    // must be in the parsed output (not just in .env.example).
    assertThat(setPaths).anyMatch(p -> p.equals("/api/v1/pm-work-orders/*/complete"));
    assertThat(setPaths).contains("/api/v1/pm-executions/*/report");
    // Story 21-1 guard: the compliance sets must also be parsed (a renamed set would
    // silently drop it from the parity check).
    assertThat(setPaths).contains("/api/v1/non-conformances/*/eight-d");
    assertThat(setPaths).contains("/api/v1/non-conformances/*/eight-d/verify-effectiveness");

    var line = Files.readAllLines(envFile).stream()
        .filter(l -> l.startsWith("SYNCRO_AUTHZ_ENFORCED_PATHS="))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("SYNCRO_AUTHZ_ENFORCED_PATHS missing from .env.example"));
    // Exact comma-separated entry match — substring checks would let
    // /api/v1/pm-executions "cover" /api/v1/pm-executions/*/report.
    var entries = Arrays.stream(line.substring("SYNCRO_AUTHZ_ENFORCED_PATHS=".length()).split(","))
        .map(String::trim)
        .toList();
    assertThat(entries).containsAll(setPaths);
  }

  /**
   * Resolves a repo-relative path by walking up from the test working directory
   * (which may be syncro/apps/backend or the repo root — no fixed ".." guess).
   */
  private static Path findRepoFile(Path repoRelative) {
    var dir = Path.of("").toAbsolutePath();
    while (dir != null) {
      var candidate = dir.resolve(repoRelative);
      if (Files.exists(candidate)) {
        return candidate;
      }
      // Maven runs tests with cwd = module dir; also try one level up (repo root).
      dir = dir.getParent();
    }
    throw new UncheckedIOException(new IOException(
        "Could not locate " + repoRelative + " by walking up from " + Path.of("").toAbsolutePath()));
  }
}
