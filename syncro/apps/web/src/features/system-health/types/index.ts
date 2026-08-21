// Types for Spring Boot Actuator /actuator/health response.
// The endpoint exposes health,info per application.yml.
// With show-details: always, each component includes status + details.

export type ActuatorStatus = "UP" | "DOWN" | "OUT_OF_SERVICE" | "UNKNOWN";

export type ActuatorHealthComponent = {
  status: ActuatorStatus;
  details?: Record<string, unknown>;
};

export type ActuatorHealthResponse = {
  status: ActuatorStatus;
  components?: {
    db?: ActuatorHealthComponent;
    redis?: ActuatorHealthComponent;
    mqtt?: ActuatorHealthComponent;
    influxdb?: ActuatorHealthComponent;
    wahaCircuitBreaker?: ActuatorHealthComponent;
    diskSpace?: ActuatorHealthComponent;
    ping?: ActuatorHealthComponent;
    [key: string]: ActuatorHealthComponent | undefined;
  };
};

// Operational worker status payloads. Field names mirror the backend records:
// IngestWorkerStatus.java / NotificationWorkerStatus.java. `status` serializes to the enum name.

export type WorkerState = "RUNNING" | "STOPPED" | "DEGRADED";

export type IngestWorkerStatus = {
  status: WorkerState;
  statusLabel: string;
  statusSeverity: string;
  statusReason: string | null;
  timestamp: string;
  mqttState: string;
  lastAcceptedAt: string | null;
  staleSince: string | null;
  queueDepth: number;
  queueCapacity: number;
  acceptedCount: number;
};

export type TelemetryFreshnessState = "NO_DATA" | "LIVE" | "STALE";

export type TelemetryFreshnessStatus = {
  status: TelemetryFreshnessState;
  statusLabel: string;
  statusSeverity: string;
  statusReason: string | null;
  timestamp: string;
  lastAcceptedAt: string | null;
  staleSince: string | null;
};

export type NotificationWorkerStatus = {
  status: WorkerState;
  statusLabel: string;
  statusSeverity: string;
  statusReason: string | null;
  timestamp: string;
  lastPollAt: string | null;
  staleSince: string | null;
  pendingJobCount: number;
  recentFailedCount: number;
  lastFailureReason: string | null;
  lastFailedAlertId: string | null;
  lastSuccessfulSendAt: string | null;
  circuitBreakerState: string | null;
};

// Per-machine stale-telemetry evidence payload. Field names mirror the backend records:
// StaleMachineStatus.java / StaleMachineItem.java. `freshnessState` is the enum name
// (OFFLINE/STALE); `lastReceivedAt` is null for machines that never sent telemetry.

export type StaleMachineItem = {
  machineId: string;
  machineCode: string;
  plantCode: string;
  freshnessState: string;
  statusLabel: string;
  lastReceivedAt: string | null;
};

export type StaleMachineStatus = {
  timestamp: string;
  staleMachineCount: number;
  items: StaleMachineItem[];
};

// Data-quality panel payload. Field names mirror the backend record:
// TelemetryDataQualityStatus.java. `status`/`latencyState` serialize to enum names;
// counts reset on backend restart (in-memory observability window, same as the ingest
// tracker), `lastLatencyMs` is null before the first latency sample (NO_DATA).

export type TelemetryDataQualityState = "GOOD" | "DEGRADED" | "CRITICAL";

export type LatencyState = "NO_DATA" | "NORMAL" | "ELEVATED" | "CRITICAL";

export type TelemetryDataQualityStatus = {
  status: TelemetryDataQualityState;
  statusLabel: string;
  statusSeverity: string;
  statusReason: string | null;
  timestamp: string;
  windowSeconds: number;
  quarantinedCount: number;
  rejectionRatePct: number;
  anomalyCount: number;
  deadLetterCount: number;
  receivedCount: number;
  quarantinedSeverity: string;
  rejectionRateSeverity: string;
  anomalySeverity: string;
  deadLetterSeverity: string;
  lastLatencyMs: number | null;
  latencyState: LatencyState;
  latencySeverity: string;
};
