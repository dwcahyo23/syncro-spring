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
 * Pins the OPA rollout parity for the PM surface (stories 19-4/19-5/19-6), the
 * compliance surfaces (stories 21-1/21-2/21-3), the auth-evidence surface
 * (story 22-2), the signature surface (story 22-3) and the webhook surface
 * (story 22-1): every path in the rego
 * pm_work_order_paths, pm_execution_paths, compliance_nc_paths,
 * compliance_eight_d_verify_paths, compliance_calibration_paths,
 * compliance_ecn_paths, compliance_ecn_approval_paths, compliance_baseline_paths,
 * compliance_lesson_paths, auth_audit_read_paths, auth_phone_challenge_paths,
 * user_signature_paths, workorder_approve_paths and admin_only_paths sets must also
 * appear in SYNCRO_AUTHZ_ENFORCED_PATHS in syncro/.env.example, or the endpoint ships
 * unenforced. The rego sets are parsed from the policy source (no hand-copied
 * list to drift). Plain file read — no Spring context; the rego suite covers the
 * policy side, this covers the env rollout side.
 */
class PmAuthzEnforcementParityTest {

  private static final Pattern SET_BODY = Pattern.compile(
      "(pm_work_order_paths|pm_execution_paths|compliance_nc_paths|compliance_eight_d_verify_paths"
          + "|compliance_calibration_paths|compliance_ecn_paths|compliance_ecn_approval_paths"
          + "|compliance_baseline_paths|compliance_lesson_paths"
          + "|auth_audit_read_paths|auth_phone_challenge_paths|user_signature_paths"
          + "|workorder_approve_paths|admin_only_paths) := \\{([^}]*)\\}");
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
    // Story 22-2 guard: the auth-evidence sets must also be parsed (same rename-drift
    // protection — a renamed set silently drops its paths from enforcement).
    assertThat(setPaths).anyMatch(p -> p.startsWith("/api/v1/auth/login-audits"));
    assertThat(setPaths).anyMatch(p -> p.startsWith("/api/v1/auth/phone-challenges"));
    // Story 22-3 guard: the signature sets must also be parsed.
    assertThat(setPaths).contains("/api/v1/auth/user-signatures");
    assertThat(setPaths).contains("/api/v1/auth/user-signatures/*");
    assertThat(setPaths).contains("/api/v1/workorders/*/approve");
    // Story 21-2 guard: the calibration + ECN sets must also be parsed (same
    // rename-drift protection — a renamed set silently drops its paths from
    // enforcement).
    assertThat(setPaths).contains("/api/v1/calibration-instruments/*/recalibrate");
    assertThat(setPaths).contains("/api/v1/calibration-instruments/*/records/*");
    assertThat(setPaths).contains("/api/v1/equipment-change-notices/*/submit");
    assertThat(setPaths).contains("/api/v1/equipment-change-notices/*/approve");
    assertThat(setPaths).contains("/api/v1/equipment-change-notices/*/execute");
    assertThat(setPaths).contains("/api/v1/equipment-change-notices/*/close");
    // Story 21-3 guard: the baseline + lesson sets must also be parsed (same
    // rename-drift protection — a renamed set silently drops its paths from
    // enforcement).
    assertThat(setPaths).contains("/api/v1/machine-setup-baselines/*/activate");
    assertThat(setPaths).contains("/api/v1/lessons-learned");
    assertThat(setPaths).contains("/api/v1/lessons-learned/*");
    // Story 22-1 guard: the admin_only_paths set (webhook surface rides it) must also
    // be parsed — a renamed set silently drops telemetry/sync/webhook from enforcement.
    assertThat(setPaths).contains("/api/v1/webhooks/**");
    assertThat(setPaths).contains("/api/v1/webhook-deliveries");
    assertThat(setPaths).contains("/api/v1/telemetry/**");
    // Story 22-4 guard: the WhatsApp message-log read surface rides admin_only_paths too.
    assertThat(setPaths).contains("/api/v1/whatsapp-message-logs");

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
