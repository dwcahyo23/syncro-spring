package com.syncro.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.AntPathMatcher;

/**
 * Pins the OPA rollout parity between the policy source and the enforced-path rollout.
 *
 * <p>{@code AuthzWebMvcConfigurer} registers the interceptor only on
 * {@code SYNCRO_AUTHZ_ENFORCED_PATHS}, so a path the env list does not match receives no
 * OPA decision at all — the endpoint ships unenforced. This test therefore parses
 * <em>every</em> {@code *_paths} set out of {@code authz.rego} (no hand-copied list to
 * drift) and asserts both directions:
 *
 * <ul>
 *   <li>forward — every rego path is matched by at least one env entry;
 *   <li>reverse — every env entry matches (or is a parent of) at least one rego path, or
 *       is a documented env-only surface. A typo'd entry matches nothing and fails here.
 * </ul>
 *
 * <p>Matching uses {@link AntPathMatcher}, the class the interceptor itself uses, so a
 * trailing {@code /**} is understood to cover its own base path. The rego suite covers
 * the policy side; this covers the rollout side. Plain file read — no Spring context.
 */
class AuthzEnforcementParityTest {

  /** {@code name := { "path", ... }} — every path set in the policy. */
  private static final Pattern PATH_SET = Pattern.compile(
      "^([a-z0-9_]+_paths)\\s*:=\\s*\\{([^}]*)\\}", Pattern.MULTILINE);
  private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

  /**
   * Enforced surfaces that carry no rego path set. Both are read-only for every
   * authenticated user through the generic {@code read_allowed} rule, so enforcement
   * adds the audit/decision trail without narrowing access.
   */
  private static final Set<String> ENV_ONLY_SURFACES = Set.of(
      "/api/v1/audit-log/**",
      "/api/v1/setup-completeness/**");

  /** A broken parser would silently "pass" both directions by finding nothing. */
  private static final int MIN_EXPECTED_SETS = 45;

  private final AntPathMatcher matcher = new AntPathMatcher();

  @Test
  @DisplayName("19.6-UNIT-001 P0 every rego path set is matched by SYNCRO_AUTHZ_ENFORCED_PATHS")
  void enforcedPathsCoverEveryRegoSet() throws IOException {
    var sets = parseRegoSets();
    var entries = parseEnforcedPaths();

    assertThat(sets).hasSizeGreaterThanOrEqualTo(MIN_EXPECTED_SETS);
    // Spot-check that the parse reached sets from different epics, so a partially
    // matching regex cannot pass by collecting only the early sets.
    assertThat(sets).containsKeys(
        "core_mutation_paths", "inventory_reservation_paths", "pm_schedule_paths",
        "compliance_ecn_paths", "user_signature_paths", "admin_only_paths");

    var uncovered = new ArrayList<String>();
    sets.forEach((set, paths) -> {
      for (var path : paths) {
        if (entries.stream().noneMatch(entry -> matcher.match(entry, path))) {
          uncovered.add(set + " -> " + path);
        }
      }
    });

    assertThat(uncovered)
        .withFailMessage("""
            These rego paths are not matched by any SYNCRO_AUTHZ_ENFORCED_PATHS entry, so
            AuthzWebMvcConfigurer never registers the interceptor for them and the endpoint
            ships unenforced:%n%s""", String.join(System.lineSeparator(), uncovered))
        .isEmpty();
  }

  @Test
  @DisplayName("19.6-UNIT-002 P0 every enforced-path entry is backed by the policy (typo guard)")
  void enforcedPathsHaveNoUnbackedEntries() throws IOException {
    var regoSets = parseRegoSets();
    var entries = parseEnforcedPaths();
    var allRegoPaths = regoSets.values().stream().flatMap(List::stream).toList();

    var unbacked = new ArrayList<String>();
    for (var entry : entries) {
      if (ENV_ONLY_SURFACES.contains(entry)) {
        continue;
      }
      var backed = allRegoPaths.stream().anyMatch(path ->
          // The entry enforces the path itself or a whole subtree of it...
          matcher.match(entry, path)
              // ...or is a parent prefix of a deeper rego path (e.g. a collection root
              // whose only mutations live at /{id}/...).
              || path.startsWith(entry + "/"));
      if (!backed) {
        unbacked.add(entry);
      }
    }

    assertThat(unbacked)
        .withFailMessage("""
            These SYNCRO_AUTHZ_ENFORCED_PATHS entries match no rego path and are not a
            documented env-only surface — likely a typo or a stale entry:%n%s""",
            String.join(System.lineSeparator(), unbacked))
        .isEmpty();
  }

  private Map<String, List<String>> parseRegoSets() throws IOException {
    var rego = Files.readString(findRepoFile(Path.of("syncro", "authz", "policy", "authz.rego")));
    var sets = new LinkedHashMap<String, List<String>>();
    var setMatcher = PATH_SET.matcher(rego);
    while (setMatcher.find()) {
      var paths = QUOTED.matcher(setMatcher.group(2)).results()
          .map(m -> m.group(1))
          .distinct()
          .toList();
      if (!paths.isEmpty()) {
        sets.put(setMatcher.group(1), paths);
      }
    }
    return sets;
  }

  private List<String> parseEnforcedPaths() throws IOException {
    var envFile = findRepoFile(Path.of("syncro", ".env.example"));
    var line = Files.readAllLines(envFile).stream()
        .filter(l -> l.startsWith("SYNCRO_AUTHZ_ENFORCED_PATHS="))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "SYNCRO_AUTHZ_ENFORCED_PATHS missing from .env.example"));
    return Arrays.stream(line.substring("SYNCRO_AUTHZ_ENFORCED_PATHS=".length()).split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
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
      dir = dir.getParent();
    }
    throw new UncheckedIOException(new IOException(
        "Could not locate " + repoRelative + " by walking up from " + Path.of("").toAbsolutePath()));
  }
}
