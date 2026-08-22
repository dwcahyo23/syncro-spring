package com.syncro.telemetry.application;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PerMachineExecution {
  private static final int STRIPES = 64;
  private final Object[] locks = new Object[STRIPES];
  public PerMachineExecution() {
    for (int i = 0; i < STRIPES; i++) {
      locks[i] = new Object();
    }
  }
  public void run(UUID machineId, Runnable criticalSection) {
    int stripe = (machineId.hashCode() & Integer.MAX_VALUE) % STRIPES;
    synchronized (locks[stripe]) {
      criticalSection.run();
    }
  }
}
