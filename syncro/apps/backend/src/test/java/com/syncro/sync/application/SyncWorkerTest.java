package com.syncro.sync.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.config.SyncProperties;
import com.syncro.sync.domain.BatchResult;
import com.syncro.sync.domain.SyncSourceRow;
import com.syncro.sync.infrastructure.SyncRunEntity;
import com.syncro.sync.infrastructure.SyncRunRepository;
import com.syncro.sync.infrastructure.SyncSourceReader;
import com.syncro.sync.infrastructure.SyncWatermarkRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Story 13-1/13-3 unit tests for {@link SyncWorker} — lock, watermark, retry, and
 * pipeline orchestration (FR-150). Story 13-3 extends the assertions to verify
 * {@code rowsRejected} is persisted via {@link BatchResult#rejected()}.
 */
@ExtendWith(MockitoExtension.class)
class SyncWorkerTest {

  private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final Duration LOCK_TTL = Duration.ofMillis(900_000L);

  @Mock
  private SyncProperties properties;
  @Mock
  private StringRedisTemplate redis;
  @Mock
  private ValueOperations<String, String> valueOps;
  @Mock
  private SyncSourceReader sourceReader;
  @Mock
  private SyncWatermarkRepository watermarks;
  @Mock
  private SyncRunRepository runs;
  @Mock
  private SyncBatchProcessor batchProcessor;

  private SyncWorker worker;

  @BeforeEach
  void setUp() {
    when(properties.lockTtlMs()).thenReturn(900_000L);
    when(redis.opsForValue()).thenReturn(valueOps);
    lenient().when(runs.saveAndFlush(any(SyncRunEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient().when(runs.findById(any())).thenAnswer(invocation -> {
      var run = new SyncRunEntity(invocation.getArgument(0), NOW, "RUNNING", 0, 0, 0, null);
      return Optional.of(run);
    });
    // Lenient to avoid UnnecessaryStubbing on the lock-held test.
    lenient().when(properties.enabled()).thenReturn(true);
    lenient().when(properties.batchSize()).thenReturn(100);
    lenient().when(watermarks.findLastSheetNo()).thenReturn(Optional.empty());

    // Inject a no-op backoff so the retry tests never sleep the real 5s.
    worker = new SyncWorker(properties, redis, sourceReader, watermarks, runs, batchProcessor,
        CLOCK, ignored -> {});
  }

  @Test
  @DisplayName("13.1-SW-001 P0 lock held: worker returns immediately, no DB query")
  void lockHeldSkipsCycle() {
    when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

    worker.poll();

    verify(sourceReader, never()).readBatch(anyString(), anyInt());
    verify(batchProcessor, never()).importBatch(any());
    verify(runs, never()).saveAndFlush(any(SyncRunEntity.class));
  }

  @Test
  @DisplayName("13.1-SW-002 P0 empty batch: run recorded with 0 rows, no watermark change")
  void emptyBatch() {
    when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    when(sourceReader.readBatch("", 100)).thenReturn(List.of());

    worker.poll();

    // saveAndFlush: once for RUNNING insert, once for SUCCESS update.
    verify(runs, times(2)).saveAndFlush(any(SyncRunEntity.class));
    verify(watermarks, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("13.3-SW-003 P0 successful batch: rows read, rows upserted, rows rejected, watermark advanced")
  void successfulBatch() {
    when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    var row = new SyncSourceRow("EXT-00001", "MC-001", "01", "OPEN", "desc", NOW, NOW, null);
    when(sourceReader.readBatch("", 100)).thenReturn(List.of(row));
    when(batchProcessor.importBatch(List.of(row)))
        .thenReturn(new BatchResult(1, 0, java.util.List.of()));

    worker.poll();

    // 2 saves: RUNNING insert + SUCCESS update.
    var runCaptor = ArgumentCaptor.forClass(SyncRunEntity.class);
    verify(runs, times(2)).saveAndFlush(runCaptor.capture());
    var completed = runCaptor.getAllValues().get(1);
    assertThat(completed.getStatus()).isEqualTo("SUCCESS");
    assertThat(completed.getRowsRead()).isEqualTo(1);
    assertThat(completed.getRowsUpserted()).isEqualTo(1);
    assertThat(completed.getRowsRejected()).isZero();
  }

  @Test
  @DisplayName("13.3-SW-003b P0 successful batch with rejections: rowsRejected persisted")
  void successfulBatchWithRejections() {
    when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    var row1 = new SyncSourceRow("EXT-00001", "MC-001", "01", "OPEN", "desc", NOW, NOW, null);
    var row2 = new SyncSourceRow("EXT-00002", "MC-001", "01", "DONE", "desc", NOW, NOW, null);
    when(sourceReader.readBatch("", 100)).thenReturn(List.of(row1, row2));
    when(batchProcessor.importBatch(List.of(row1, row2)))
        .thenReturn(new BatchResult(1, 1, java.util.List.of()));

    worker.poll();

    var runCaptor = ArgumentCaptor.forClass(SyncRunEntity.class);
    verify(runs, times(2)).saveAndFlush(runCaptor.capture());
    var completed = runCaptor.getAllValues().get(1);
    assertThat(completed.getStatus()).isEqualTo("SUCCESS");
    assertThat(completed.getRowsRead()).isEqualTo(2);
    assertThat(completed.getRowsUpserted()).isEqualTo(1);
    assertThat(completed.getRowsRejected()).isEqualTo(1);
  }

  @Test
  @DisplayName("13.1-SW-004 P0 external DB down: retries 3x, then cycle FAILED, schedule survives")
  void externalDbDownRetriesThenFails() {
    AtomicReference<String> lockValue = new AtomicReference<>();
    when(valueOps.setIfAbsent(eq(SyncWorker.LOCK_KEY), anyString(), eq(LOCK_TTL)))
        .thenAnswer(invocation -> {
          lockValue.set(invocation.getArgument(1));
          return true;
        });
    when(valueOps.get(SyncWorker.LOCK_KEY)).thenAnswer(invocation -> lockValue.get());
    when(sourceReader.readBatch("", 100))
        .thenThrow(new RuntimeException("Connection refused"))
        .thenThrow(new RuntimeException("Connection refused"))
        .thenThrow(new RuntimeException("Connection refused"));

    worker.poll();

    var runCaptor = ArgumentCaptor.forClass(SyncRunEntity.class);
    verify(runs, times(2)).saveAndFlush(runCaptor.capture());
    var failed = runCaptor.getAllValues().get(1);
    assertThat(failed.getStatus()).isEqualTo("FAILED");
    assertThat(failed.getRowsRead()).isZero();
    assertThat(failed.getRowsRejected()).isZero();
    assertThat(failed.getErrorMessage()).isNotNull();
    // Lock released after failure (finally block).
    verify(redis).delete(SyncWorker.LOCK_KEY);
  }

  @Test
  @DisplayName("13.1-SW-005 P0 processor failure: reader succeeds, batchProcessor throws, cycle FAILED")
  void processorFailure() {
    AtomicReference<String> lockValue = new AtomicReference<>();
    when(valueOps.setIfAbsent(eq(SyncWorker.LOCK_KEY), anyString(), eq(LOCK_TTL)))
        .thenAnswer(invocation -> {
          lockValue.set(invocation.getArgument(1));
          return true;
        });
    when(valueOps.get(SyncWorker.LOCK_KEY)).thenAnswer(invocation -> lockValue.get());
    var row = new SyncSourceRow("EXT-00001", "MC-001", "01", "OPEN", "desc", NOW, NOW, null);
    when(sourceReader.readBatch("", 100)).thenReturn(List.of(row));
    when(batchProcessor.importBatch(List.of(row))).thenThrow(new RuntimeException("constraint violation"));

    worker.poll();

    var runCaptor = ArgumentCaptor.forClass(SyncRunEntity.class);
    verify(runs, times(2)).saveAndFlush(runCaptor.capture());
    var failed = runCaptor.getAllValues().get(1);
    assertThat(failed.getStatus()).isEqualTo("FAILED");
    // totalRead only counts rows whose batch import completed; a throwing batch
    // contributes 0 (consistent with the reader-failure path).
    assertThat(failed.getRowsRead()).isZero();
    assertThat(failed.getRowsUpserted()).isZero();
    assertThat(failed.getRowsRejected()).isZero();
    assertThat(failed.getErrorMessage()).isNotNull();
    // Lock released after failure (finally block).
    verify(redis).delete(SyncWorker.LOCK_KEY);
  }

  @Test
  @DisplayName("13.1-SW-006 P0 lock released after cycle (finally block)")
  void lockReleasedInFinally() {
    AtomicReference<String> lockValue = new AtomicReference<>();
    when(valueOps.setIfAbsent(eq(SyncWorker.LOCK_KEY), anyString(), eq(LOCK_TTL)))
        .thenAnswer(invocation -> {
          lockValue.set(invocation.getArgument(1));
          return true;
        });
    when(valueOps.get(SyncWorker.LOCK_KEY)).thenAnswer(invocation -> lockValue.get());
    when(sourceReader.readBatch("", 100)).thenReturn(List.of());

    worker.poll();

    verify(redis).delete(SyncWorker.LOCK_KEY);
  }
}