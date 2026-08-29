package com.syncro.sparepart.application;

import com.syncro.sparepart.infrastructure.MachineSparepartInstallationEntity;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationRepository;
import com.syncro.telemetry.application.CountingDeltaCalculator;
import com.syncro.telemetry.infrastructure.RedisLatestTelemetryWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SparepartLifetimeEvaluator {

  private final MachineSparepartInstallationRepository installationRepository;
  private final RedisLatestTelemetryWriter redisLatestWriter;

  public SparepartLifetimeEvaluator(MachineSparepartInstallationRepository installationRepository,
      RedisLatestTelemetryWriter redisLatestWriter) {
    this.installationRepository = installationRepository;
    this.redisLatestWriter = redisLatestWriter;
  }

  public record EvaluationResult(Long currentCount, Long consumedProductionCount, BigDecimal consumedPercentage) {
  }

  public Optional<EvaluationResult> evaluate(UUID machineId, UUID installationId) {
    Optional<Long> currentCountOpt = redisLatestWriter.readCounting(machineId);
    if (currentCountOpt.isEmpty()) {
      return Optional.empty();
    }
    long current = currentCountOpt.get();
    MachineSparepartInstallationEntity installation = installationRepository.findById(installationId)
        .orElse(null);
    if (installation == null) {
      return Optional.empty();
    }
    if (installation.getExpectedProductionCount() == 0) {
      return Optional.empty();
    }
    long consumed = CountingDeltaCalculator.delta(installation.getBaselineCounter(), current);
    BigDecimal pct = BigDecimal.valueOf(consumed)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(installation.getExpectedProductionCount()), 2, RoundingMode.HALF_UP);
    return Optional.of(new EvaluationResult(current, consumed, pct));
  }

  public Map<UUID, EvaluationResult> evaluateAll(UUID machineId) {
    List<MachineSparepartInstallationEntity> installations =
        installationRepository.findAllByMachineId(machineId);
    if (installations.isEmpty()) {
      return Map.of();
    }
    Optional<Long> currentCountOpt = redisLatestWriter.readCounting(machineId);
    if (currentCountOpt.isEmpty()) {
      return Map.of();
    }
    long current = currentCountOpt.get();
    Map<UUID, EvaluationResult> results = new HashMap<>();
    for (MachineSparepartInstallationEntity installation : installations) {
      if (installation.getExpectedProductionCount() == 0) {
        continue;
      }
      long consumed = CountingDeltaCalculator.delta(installation.getBaselineCounter(), current);
      BigDecimal pct = BigDecimal.valueOf(consumed)
          .multiply(BigDecimal.valueOf(100))
          .divide(BigDecimal.valueOf(installation.getExpectedProductionCount()), 2, RoundingMode.HALF_UP);
      results.put(installation.getId(), new EvaluationResult(current, consumed, pct));
    }
    return results;
  }

  /**
   * Batch lifetime evaluation across many machines (story 14-1, FR-170): reads all
   * counting values in ONE pipelined Redis round trip and evaluates every installation
   * whose machine has data — no per-machine N+1. Result keyed by machine id → its
   * installation-id → evaluation. Machines without Redis data are absent.
   */
  public Map<UUID, Map<UUID, EvaluationResult>> evaluateBatch(Collection<UUID> machineIds) {
    if (machineIds == null || machineIds.isEmpty()) {
      return Map.of();
    }
    List<UUID> ids = machineIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Long> countingByMachine = redisLatestWriter.readCountingBatch(ids);
    if (countingByMachine.isEmpty()) {
      return Map.of();
    }
    List<MachineSparepartInstallationEntity> installations =
        installationRepository.findAllByMachineIdIn(ids);
    Map<UUID, Map<UUID, EvaluationResult>> resultsByMachine = new HashMap<>();
    for (MachineSparepartInstallationEntity installation : installations) {
      var machineId = installation.getMachine().getId();
      Long current = countingByMachine.get(machineId);
      if (current == null || installation.getExpectedProductionCount() == 0) {
        continue;
      }
      long consumed = CountingDeltaCalculator.delta(installation.getBaselineCounter(), current);
      BigDecimal pct = BigDecimal.valueOf(consumed)
          .multiply(BigDecimal.valueOf(100))
          .divide(BigDecimal.valueOf(installation.getExpectedProductionCount()), 2, RoundingMode.HALF_UP);
      resultsByMachine
          .computeIfAbsent(machineId, ignored -> new HashMap<>())
          .put(installation.getId(), new EvaluationResult(current, consumed, pct));
    }
    return resultsByMachine;
  }
}
