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
  lastSuccessfulSendAt: string | null;
  circuitBreakerState: string | null;
};
