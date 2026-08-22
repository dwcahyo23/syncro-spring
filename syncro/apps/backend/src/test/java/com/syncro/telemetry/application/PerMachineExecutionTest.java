package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PerMachineExecutionTest {

  private final PerMachineExecution execution = new PerMachineExecution();

  @Test
  void sameMachineCriticalSectionsDoNotOverlap() throws InterruptedException {
    var machineId = UUID.randomUUID();
    var active = new AtomicInteger();
    var maxActive = new AtomicInteger();
    var ready = new CountDownLatch(2);
    var go = new CountDownLatch(1);
    Runnable critical = () -> {
      int now = active.incrementAndGet();
      maxActive.accumulateAndGet(now, Math::max);
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      active.decrementAndGet();
    };
    var t1 = new Thread(() -> {
      ready.countDown();
      try { go.await(1, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      execution.run(machineId, critical);
    });
    var t2 = new Thread(() -> {
      ready.countDown();
      try { go.await(1, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      execution.run(machineId, critical);
    });
    t1.start();
    t2.start();
    ready.await(1, TimeUnit.SECONDS);
    go.countDown();
    t1.join();
    t2.join();
    assertThat(maxActive.get()).isEqualTo(1);
  }

  @Test
  void differentMachinesRunInParallel() throws InterruptedException {
    var machineA = UUID.randomUUID();
    var machineB = machineOnDifferentStripe(machineA);
    var active = new AtomicInteger();
    var maxActive = new AtomicInteger();
    var latch = new CountDownLatch(1);
    Runnable critical = () -> {
      latch.countDown();
      try {
        latch.await(1, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      int now = active.incrementAndGet();
      maxActive.accumulateAndGet(now, Math::max);
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      active.decrementAndGet();
    };
    var t1 = new Thread(() -> execution.run(machineA, critical));
    var t2 = new Thread(() -> execution.run(machineB, critical));
    t1.start();
    t2.start();
    latch.await(1, TimeUnit.SECONDS);
    t1.join();
    t2.join();
    assertThat(maxActive.get()).isEqualTo(2);
  }

  private static UUID machineOnDifferentStripe(UUID machineA) {
    int stripeA = (machineA.hashCode() & Integer.MAX_VALUE) % 64;
    UUID candidate;
    do {
      candidate = UUID.randomUUID();
    } while (((candidate.hashCode() & Integer.MAX_VALUE) % 64) == stripeA);
    return candidate;
  }
}