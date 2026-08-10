import type { MachineView } from "@/lib/api/generated/model";

export type TelemetryFreshnessState = "ONLINE" | "OFFLINE" | "STALE";

export type LatestTelemetry = {
  machineId?: string;
  running?: boolean;
  runtimeHours?: number | null;
  counting?: number | null;
  lastReceivedAt?: string | null;
  freshnessState?: TelemetryFreshnessState;
  optionalFields?: Record<string, string>;
  hasOptionalFields?: boolean;
};

export type TelemetryMachineView = MachineView & {
  optionalTelemetryFields?: string[];
  latestTelemetry?: LatestTelemetry | null;
};
