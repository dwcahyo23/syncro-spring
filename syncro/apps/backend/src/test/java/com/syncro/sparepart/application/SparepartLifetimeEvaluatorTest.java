package com.syncro.sparepart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.syncro.sparepart.infrastructure.MachineSparepartInstallationEntity;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationRepository;
import com.syncro.telemetry.infrastructure.RedisLatestTelemetryWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SparepartLifetimeEvaluatorTest {

  @Mock
  private MachineSparepartInstallationRepository installationRepository;
  @Mock
  private RedisLatestTelemetryWriter redisLatestWriter;
  @Mock
  private MachineSparepartInstallationEntity installationEntity;

  private SparepartLifetimeEvaluator evaluator;

  private static final UUID MACHINE_ID = UUID.randomUUID();
  private static final UUID INSTALLATION_ID = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    evaluator = new SparepartLifetimeEvaluator(installationRepository, redisLatestWriter);
  }

  @Test
  void evaluate_happyPath_returnsCorrectValues() {
    // baseline=100, expected=1000, current=350 -> consumed=250, percentage=25.00
    when(redisLatestWriter.readCounting(MACHINE_ID)).thenReturn(Optional.of(350L));
    when(installationRepository.findById(INSTALLATION_ID)).thenReturn(Optional.of(installationEntity));
    when(installationEntity.getBaselineCounter()).thenReturn(100L);
    when(installationEntity.getExpectedProductionCount()).thenReturn(1000L);

    Optional<SparepartLifetimeEvaluator.EvaluationResult> result =
        evaluator.evaluate(MACHINE_ID, INSTALLATION_ID);

    assertThat(result).isPresent();
    assertThat(result.get().currentCount()).isEqualTo(350L);
    assertThat(result.get().consumedProductionCount()).isEqualTo(250L);
    assertThat(result.get().consumedPercentage()).isEqualByComparingTo(new BigDecimal("25.00"));
  }

  @Test
  void evaluate_counterWrap_returnsCorrectDelta() {
    // baseline=65000, expected=1000, current=100 -> consumed=636 (wrap around 65536)
    when(redisLatestWriter.readCounting(MACHINE_ID)).thenReturn(Optional.of(100L));
    when(installationRepository.findById(INSTALLATION_ID)).thenReturn(Optional.of(installationEntity));
    when(installationEntity.getBaselineCounter()).thenReturn(65000L);
    when(installationEntity.getExpectedProductionCount()).thenReturn(1000L);

    Optional<SparepartLifetimeEvaluator.EvaluationResult> result =
        evaluator.evaluate(MACHINE_ID, INSTALLATION_ID);

    assertThat(result).isPresent();
    // Math.floorMod(100 - 65000, 65536) = Math.floorMod(-64900, 65536) = 636
    assertThat(result.get().consumedProductionCount()).isEqualTo(636L);
  }

  @Test
  void evaluate_redisMiss_returnsEmpty() {
    when(redisLatestWriter.readCounting(MACHINE_ID)).thenReturn(Optional.empty());

    Optional<SparepartLifetimeEvaluator.EvaluationResult> result =
        evaluator.evaluate(MACHINE_ID, INSTALLATION_ID);

    assertThat(result).isEmpty();
  }

  @Test
  void evaluateAll_noInstallations_returnsEmptyMap() {
    when(installationRepository.findAllByMachineId(MACHINE_ID)).thenReturn(List.of());

    Map<UUID, SparepartLifetimeEvaluator.EvaluationResult> result =
        evaluator.evaluateAll(MACHINE_ID);

    assertThat(result).isEmpty();
  }

  @Test
  void evaluateAll_redisMiss_returnsEmptyMap() {
    when(installationRepository.findAllByMachineId(MACHINE_ID)).thenReturn(List.of(installationEntity));
    when(redisLatestWriter.readCounting(MACHINE_ID)).thenReturn(Optional.empty());

    Map<UUID, SparepartLifetimeEvaluator.EvaluationResult> result =
        evaluator.evaluateAll(MACHINE_ID);

    assertThat(result).isEmpty();
  }

  @Test
  void evaluate_findByIdReturnsEmpty_returnsEmpty() {
    when(redisLatestWriter.readCounting(MACHINE_ID)).thenReturn(Optional.of(350L));
    when(installationRepository.findById(INSTALLATION_ID)).thenReturn(Optional.empty());

    Optional<SparepartLifetimeEvaluator.EvaluationResult> result =
        evaluator.evaluate(MACHINE_ID, INSTALLATION_ID);

    assertThat(result).isEmpty();
  }

  @Test
  void evaluate_zeroExpectedProductionCount_returnsEmpty() {
    when(redisLatestWriter.readCounting(MACHINE_ID)).thenReturn(Optional.of(350L));
    when(installationRepository.findById(INSTALLATION_ID)).thenReturn(Optional.of(installationEntity));
    when(installationEntity.getExpectedProductionCount()).thenReturn(0L);

    Optional<SparepartLifetimeEvaluator.EvaluationResult> result =
        evaluator.evaluate(MACHINE_ID, INSTALLATION_ID);

    assertThat(result).isEmpty();
  }

  @Test
  void evaluateAll_zeroExpectedProductionCount_skipsInstallation() {
    when(installationRepository.findAllByMachineId(MACHINE_ID)).thenReturn(List.of(installationEntity));
    when(redisLatestWriter.readCounting(MACHINE_ID)).thenReturn(Optional.of(350L));
    when(installationEntity.getExpectedProductionCount()).thenReturn(0L);

    Map<UUID, SparepartLifetimeEvaluator.EvaluationResult> result =
        evaluator.evaluateAll(MACHINE_ID);

    assertThat(result).isEmpty();
  }

  @Test
  void evaluateAll_happyPath_returnsPopulatedMap() {
    UUID installationId = UUID.randomUUID();
    when(installationRepository.findAllByMachineId(MACHINE_ID)).thenReturn(List.of(installationEntity));
    when(redisLatestWriter.readCounting(MACHINE_ID)).thenReturn(Optional.of(350L));
    when(installationEntity.getId()).thenReturn(installationId);
    when(installationEntity.getBaselineCounter()).thenReturn(100L);
    when(installationEntity.getExpectedProductionCount()).thenReturn(1000L);

    Map<UUID, SparepartLifetimeEvaluator.EvaluationResult> result =
        evaluator.evaluateAll(MACHINE_ID);

    assertThat(result).containsKey(installationId);
    assertThat(result.get(installationId).currentCount()).isEqualTo(350L);
    assertThat(result.get(installationId).consumedProductionCount()).isEqualTo(250L);
    assertThat(result.get(installationId).consumedPercentage()).isEqualByComparingTo(new BigDecimal("25.00"));
  }
}
