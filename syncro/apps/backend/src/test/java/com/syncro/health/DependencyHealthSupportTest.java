package com.syncro.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class DependencyHealthSupportTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ZoneOffset.UTC);
  private static final String FIXED_TIMESTAMP = "2026-08-21T08:00:00Z";

  @Test
  void statusLabelMapsCanonicalStatuses() {
    assertThat(DependencyHealthSupport.statusLabel(Status.UP)).isEqualTo("Up");
    assertThat(DependencyHealthSupport.statusLabel(Status.DOWN)).isEqualTo("Down");
    assertThat(DependencyHealthSupport.statusLabel(Status.OUT_OF_SERVICE)).isEqualTo("Out of Service");
    assertThat(DependencyHealthSupport.statusLabel(Status.UNKNOWN)).isEqualTo("Unknown");
  }

  @Test
  void statusSeverityMapsCanonicalStatuses() {
    assertThat(DependencyHealthSupport.statusSeverity(Status.UP)).isEqualTo("SUCCESS");
    assertThat(DependencyHealthSupport.statusSeverity(Status.DOWN)).isEqualTo("CRITICAL");
    assertThat(DependencyHealthSupport.statusSeverity(Status.OUT_OF_SERVICE)).isEqualTo("WARNING");
    assertThat(DependencyHealthSupport.statusSeverity(Status.UNKNOWN)).isEqualTo("NEUTRAL");
  }

  @Test
  void enrichAddsContractFieldsWithoutReasonOrTraceId() {
    Health enriched = DependencyHealthSupport.enrich(Health.up().build(), FIXED_CLOCK);

    assertThat(enriched.getStatus()).isEqualTo(Status.UP);
    assertThat(enriched.getDetails())
        .containsEntry("statusLabel", "Up")
        .containsEntry("statusSeverity", "SUCCESS")
        .containsEntry("timestamp", FIXED_TIMESTAMP);
    assertThat(enriched.getDetails()).doesNotContainKeys("statusReason", "traceId");
  }

  @Test
  void enrichAddsStatusReasonAndTraceIdWhenProvided() {
    Health enriched = DependencyHealthSupport.enrich(
        Health.down().build(), FIXED_CLOCK, "boom", "trace-123");

    assertThat(enriched.getStatus()).isEqualTo(Status.DOWN);
    assertThat(enriched.getDetails())
        .containsEntry("statusLabel", "Down")
        .containsEntry("statusSeverity", "CRITICAL")
        .containsEntry("statusReason", "boom")
        .containsEntry("traceId", "trace-123")
        .containsEntry("timestamp", FIXED_TIMESTAMP);
  }

  @Test
  void enrichPreservesIndicatorSpecificDetails() {
    Health raw = Health.up().withDetail("database", "PostgreSQL").build();

    Health enriched = DependencyHealthSupport.enrich(raw, FIXED_CLOCK);

    assertThat(enriched.getDetails())
        .containsEntry("database", "PostgreSQL")
        .containsEntry("statusLabel", "Up")
        .containsEntry("statusSeverity", "SUCCESS");
  }
}