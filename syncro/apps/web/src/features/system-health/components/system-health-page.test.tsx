import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { formatDateTimeUtc } from "@/components/syncro/health-card";
import { RoleGuard } from "@/components/syncro/role-guard";
import type {
  ActuatorHealthComponent,
  ActuatorHealthResponse,
  IngestWorkerStatus,
  NotificationWorkerStatus,
  StaleMachineStatus,
  TelemetryDataQualityStatus,
  TelemetryFreshnessStatus,
} from "@/features/system-health/types";
import { I18nProvider } from "@/test/i18n-wrapper";

import { computeOverallBanner, formatRelativeFreshness, SystemHealthPage } from "./system-health-page";

// ─── Mock modules ───────────────────────────────────────────────────────────────

let actuatorQuery: HealthQueryState;
let ingestQuery: WorkerQueryState;
let notifQuery: WorkerQueryState;
let quarantineQuery: QuarantineQueryState;
let freshnessQuery: FreshnessQueryState;
let staleMachinesQuery: StaleMachinesQueryState;
let dataQualityQuery: DataQualityQueryState;

vi.mock("@/features/system-health/hooks/use-actuator-health-query", () => ({
  SYSTEM_HEALTH_REFRESH_INTERVAL_MS: 30_000,
  useActuatorHealthQuery: () => actuatorQuery,
}));

vi.mock("@/features/system-health/hooks/use-data-quality", () => ({
  useDataQuality: () => dataQualityQuery,
}));

vi.mock("@/features/system-health/hooks/use-ingest-worker-status", () => ({
  useIngestWorkerStatus: () => ingestQuery,
}));

vi.mock("@/features/system-health/hooks/use-notification-worker-status", () => ({
  useNotificationWorkerStatus: () => notifQuery,
}));

vi.mock("@/features/system-health/hooks/use-quarantine-log", () => ({
  useQuarantineLog: () => quarantineQuery,
}));

vi.mock("@/features/system-health/hooks/use-telemetry-freshness", () => ({
  useTelemetryFreshness: () => freshnessQuery,
}));

vi.mock("@/features/system-health/hooks/use-stale-machines", () => ({
  useStaleMachines: () => staleMachinesQuery,
}));

let mockUser: { id: string; loginIdentifier: string; applicationRole: string } | null = null;

vi.mock("@/lib/auth/use-auth-user", () => ({
  useAuthUser: () => mockUser,
}));

vi.mock("next/link", () => {
  const Link = ({ children, href }: { children: React.ReactNode; href: string }) => <a href={href}>{children}</a>;
  return { default: Link };
});

// ─── Types ──────────────────────────────────────────────────────────────────────

type HealthQueryState = {
  data: ActuatorHealthResponse;
  dataUpdatedAt?: number;
  isLoading: boolean;
  isError: boolean;
  isFetching: boolean;
  refetch: ReturnType<typeof vi.fn>;
};

type WorkerQueryState = {
  data: IngestWorkerStatus | NotificationWorkerStatus | undefined;
  dataUpdatedAt?: number;
  isLoading: boolean;
  isError: boolean;
  isFetching: boolean;
  refetch: ReturnType<typeof vi.fn>;
};

type FreshnessQueryState = {
  data: TelemetryFreshnessStatus | undefined;
  dataUpdatedAt?: number;
  isLoading: boolean;
  isError: boolean;
  isFetching: boolean;
  refetch: ReturnType<typeof vi.fn>;
};

type StaleMachinesQueryState = {
  data: StaleMachineStatus | undefined;
  dataUpdatedAt?: number;
  isLoading: boolean;
  isError: boolean;
  isFetching: boolean;
  refetch: ReturnType<typeof vi.fn>;
};

type DataQualityQueryState = {
  data: TelemetryDataQualityStatus | undefined;
  dataUpdatedAt?: number;
  isLoading: boolean;
  isError: boolean;
  isFetching: boolean;
  refetch: ReturnType<typeof vi.fn>;
};

type QuarantineQueryState = {
  data: { content: never[]; number: number; totalPages: number } | undefined;
  isLoading: boolean;
  isError: boolean;
  refetch: ReturnType<typeof vi.fn>;
};

// ─── Factory helpers ────────────────────────────────────────────────────────────

function healthyComponents(): NonNullable<ActuatorHealthResponse["components"]> {
  return {
    db: healthyComponent("Up", "SUCCESS"),
    influxdb: healthyComponent("Up", "SUCCESS"),
    redis: healthyComponent("Up", "SUCCESS"),
    mqtt: healthyComponent("Up", "SUCCESS"),
    wahaCircuitBreaker: healthyComponent("Up", "SUCCESS", { state: "CLOSED", failureRate: 0 }),
  };
}

function healthyActuator(overrides: Partial<ActuatorHealthResponse> = {}): ActuatorHealthResponse {
  return {
    status: "UP",
    components: healthyComponents(),
    ...overrides,
  };
}

function healthyComponent(
  statusLabel: string,
  statusSeverity: string,
  extra: Record<string, unknown> = {},
): ActuatorHealthComponent {
  return {
    status: statusLabel === "Up" ? "UP" : "DOWN",
    details: {
      statusLabel,
      statusSeverity,
      statusReason: null,
      timestamp: "2026-08-21T10:00:00.000Z",
      ...extra,
    },
  };
}

const healthyIngest: IngestWorkerStatus = {
  status: "RUNNING",
  statusLabel: "Running",
  statusSeverity: "SUCCESS",
  statusReason: null,
  timestamp: "2026-08-21T10:00:00.000Z",
  mqttState: "SUBSCRIBED",
  lastAcceptedAt: "2026-08-21T09:59:00.000Z",
  staleSince: null,
  queueDepth: 0,
  queueCapacity: 1000,
  acceptedCount: 42,
};

const healthyNotif: NotificationWorkerStatus = {
  status: "RUNNING",
  statusLabel: "Running",
  statusSeverity: "SUCCESS",
  statusReason: null,
  timestamp: "2026-08-21T10:00:00.000Z",
  lastPollAt: "2026-08-21T10:00:00.000Z",
  staleSince: null,
  pendingJobCount: 3,
  recentFailedCount: 0,
  lastFailureReason: null,
  lastFailedAlertId: null,
  lastSuccessfulSendAt: "2026-08-21T09:55:00.000Z",
  circuitBreakerState: "CLOSED",
};

function healthyFreshness(overrides: Partial<TelemetryFreshnessStatus> = {}): TelemetryFreshnessStatus {
  return {
    status: "LIVE",
    statusLabel: "Live",
    statusSeverity: "SUCCESS",
    statusReason: null,
    timestamp: "2026-08-21T10:00:00.000Z",
    lastAcceptedAt: "2026-08-21T09:59:00.000Z",
    staleSince: null,
    ...overrides,
  };
}

function healthyStaleMachines(overrides: Partial<StaleMachineStatus> = {}): StaleMachineStatus {
  return {
    timestamp: "2026-08-21T10:00:00.000Z",
    staleMachineCount: 0,
    items: [],
    ...overrides,
  };
}

function healthyDataQuality(overrides: Partial<TelemetryDataQualityStatus> = {}): TelemetryDataQualityStatus {
  return {
    status: "GOOD",
    statusLabel: "Good",
    statusSeverity: "SUCCESS",
    statusReason: null,
    timestamp: "2026-08-22T10:00:00.000Z",
    windowSeconds: 3600,
    quarantinedCount: 2,
    rejectionRatePct: 0.2,
    anomalyCount: 0,
    deadLetterCount: 0,
    receivedCount: 1002,
    quarantinedSeverity: "SUCCESS",
    rejectionRateSeverity: "SUCCESS",
    anomalySeverity: "SUCCESS",
    deadLetterSeverity: "SUCCESS",
    lastLatencyMs: 800,
    latencyState: "NORMAL",
    latencySeverity: "SUCCESS",
    ...overrides,
  };
}

/** Finds the Telemetry Freshness card within its section, since the h2 heading also matches the title text. */
function freshnessCard(): HTMLElement {
  const section = screen.getByRole("region", { name: "Telemetry Freshness" });
  const card = section.querySelector('[data-slot="card"]');
  if (!card) throw new Error("Telemetry Freshness card not found");
  return card as HTMLElement;
}

// ─── Test wrapper ───────────────────────────────────────────────────────────────

const queryClient = new QueryClient();

const Wrapper = ({ children }: { children: React.ReactNode }) => (
  <I18nProvider>
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  </I18nProvider>
);

// ─── Tests ──────────────────────────────────────────────────────────────────────

describe("System Health Page", () => {
  beforeEach(() => {
    mockUser = null;
    const now = Date.now();
    actuatorQuery = {
      data: healthyActuator(),
      dataUpdatedAt: now,
      isLoading: false,
      isError: false,
      isFetching: false,
      refetch: vi.fn(),
    };
    ingestQuery = {
      data: healthyIngest,
      dataUpdatedAt: now,
      isLoading: false,
      isError: false,
      isFetching: false,
      refetch: vi.fn(),
    };
    notifQuery = {
      data: healthyNotif,
      dataUpdatedAt: now,
      isLoading: false,
      isError: false,
      isFetching: false,
      refetch: vi.fn(),
    };
    quarantineQuery = {
      data: { content: [], number: 0, totalPages: 0 },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };
    freshnessQuery = {
      data: healthyFreshness(),
      dataUpdatedAt: now,
      isLoading: false,
      isError: false,
      isFetching: false,
      refetch: vi.fn(),
    };
    staleMachinesQuery = {
      data: healthyStaleMachines(),
      dataUpdatedAt: now,
      isLoading: false,
      isError: false,
      isFetching: false,
      refetch: vi.fn(),
    };
    dataQualityQuery = {
      data: healthyDataQuality(),
      dataUpdatedAt: now,
      isLoading: false,
      isError: false,
      isFetching: false,
      refetch: vi.fn(),
    };
  });

  it("6-4-AC1 renders all eight cards: PostgreSQL, InfluxDB, Redis, MQTT, WAHA, Telemetry Ingest Worker, Notification Worker, Telemetry Freshness", () => {
    render(<SystemHealthPage />, { wrapper: Wrapper });

    expect(screen.getByText("PostgreSQL")).toBeInTheDocument();
    expect(screen.getByText("InfluxDB")).toBeInTheDocument();
    expect(screen.getByText("Redis")).toBeInTheDocument();
    expect(screen.getByText("MQTT / EMQX")).toBeInTheDocument();
    expect(screen.getByText("WAHA")).toBeInTheDocument();
    expect(screen.getByText("Telemetry Ingest Worker")).toBeInTheDocument();
    expect(screen.getByText("Notification Worker")).toBeInTheDocument();
    expect(freshnessCard()).toBeInTheDocument();
  });

  it("6-4-AC2 a healthy card shows status label, severity, reason, and timestamp", () => {
    const dbDown: ActuatorHealthComponent = {
      status: "DOWN",
      details: {
        statusLabel: "Down",
        statusSeverity: "CRITICAL",
        statusReason: "CONNECTION_FAILED",
        timestamp: "2026-08-21T10:00:00.000Z",
      },
    };
    actuatorQuery = {
      ...actuatorQuery,
      data: healthyActuator({ components: { ...healthyComponents(), db: dbDown } }),
    };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    const card = screen.getByText("PostgreSQL").closest('[data-slot="card"]');
    if (!card) throw new Error("PostgreSQL card not found");

    expect(card).toHaveTextContent("Down");
    expect(card).toHaveTextContent("Critical");
    expect(card).toHaveTextContent("CONNECTION_FAILED");
    expect(card).toHaveTextContent(/2026/);
    expect(card).toHaveTextContent(/UTC/);
  });

  it("6-4-AC4-loading renders skeletons", () => {
    actuatorQuery = { ...actuatorQuery, isLoading: true, data: undefined as unknown as ActuatorHealthResponse };
    ingestQuery = { ...ingestQuery, isLoading: true, data: undefined };
    notifQuery = { ...notifQuery, isLoading: true, data: undefined };
    freshnessQuery = { ...freshnessQuery, isLoading: true, data: undefined };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    const skeletons = document.querySelectorAll('[data-slot="skeleton"]');
    expect(skeletons.length).toBeGreaterThan(0);
    expect(screen.queryByText("All systems operational.")).not.toBeInTheDocument();
  });

  it("6-4-AC4-error renders per-card Unable to check fallback", () => {
    actuatorQuery = { ...actuatorQuery, isError: true, data: undefined as unknown as ActuatorHealthResponse };
    ingestQuery = { ...ingestQuery, isError: true, data: undefined };
    notifQuery = { ...notifQuery, isError: true, data: undefined };
    freshnessQuery = { ...freshnessQuery, isError: true, data: undefined };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    expect(screen.getByText("Unable to check PostgreSQL.")).toBeInTheDocument();
    expect(screen.getByText("Unable to check InfluxDB.")).toBeInTheDocument();
    expect(screen.getByText("Unable to check Redis.")).toBeInTheDocument();
    expect(screen.getByText("Unable to check MQTT / EMQX.")).toBeInTheDocument();
    expect(screen.getByText("Unable to check WAHA.")).toBeInTheDocument();
    expect(screen.getByText("Unable to check Telemetry Ingest Worker.")).toBeInTheDocument();
    expect(screen.getByText("Unable to check Notification Worker.")).toBeInTheDocument();
    expect(screen.getByText("Unable to check Telemetry Freshness.")).toBeInTheDocument();
    expect(screen.getByText(/degraded or could not be verified/)).toBeInTheDocument();
  });

  it("6-4-AC4-empty renders No health data reported", () => {
    actuatorQuery = {
      ...actuatorQuery,
      data: { status: "UP", components: {} },
    };
    ingestQuery = { ...ingestQuery, data: undefined };
    notifQuery = { ...notifQuery, data: undefined };
    freshnessQuery = { ...freshnessQuery, data: undefined };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    const emptyMessages = screen.getAllByText("No health data reported.");
    expect(emptyMessages.length).toBe(8);
  });

  it("shows last known status (not just the error text) when a refetch fails with cached data", () => {
    ingestQuery = { ...ingestQuery, isError: true };
    notifQuery = { ...notifQuery, isError: true };
    freshnessQuery = { ...freshnessQuery, isError: true };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    expect(screen.getByText(/Unable to refresh Telemetry Ingest Worker/)).toBeInTheDocument();
    expect(screen.getByText(/Unable to refresh Notification Worker/)).toBeInTheDocument();
    expect(screen.getByText(/Unable to refresh Telemetry Freshness/)).toBeInTheDocument();
    expect(screen.getAllByText("Running").length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText("42")).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();
    expect(screen.getByText("Live")).toBeInTheDocument();
  });

  it("renders a dash for a non-finite WAHA failure rate", () => {
    actuatorQuery = {
      ...actuatorQuery,
      data: healthyActuator({
        components: {
          ...healthyComponents(),
          wahaCircuitBreaker: healthyComponent("Up", "SUCCESS", { state: "CLOSED", failureRate: Number.NaN }),
        },
      }),
    };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    const card = screen.getByText("WAHA").closest('[data-slot="card"]');
    if (!card) throw new Error("WAHA card not found");
    expect(card).toHaveTextContent("Failure rate");
    expect(card).toHaveTextContent("—");
    expect(card).not.toHaveTextContent("NaN");
  });

  it("renders a dash for null worker counts and empty-string metrics", () => {
    ingestQuery = {
      ...ingestQuery,
      data: {
        ...healthyIngest,
        acceptedCount: null as unknown as number,
        queueDepth: null as unknown as number,
        queueCapacity: null as unknown as number,
        mqttState: "",
      },
    };
    notifQuery = {
      ...notifQuery,
      data: {
        ...healthyNotif,
        pendingJobCount: null as unknown as number,
        lastFailureReason: "",
        circuitBreakerState: "",
      },
    };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    const ingestCard = screen.getByText("Telemetry Ingest Worker").closest('[data-slot="card"]');
    if (!ingestCard) throw new Error("Ingest card not found");
    expect(ingestCard).not.toHaveTextContent("null");
    expect(ingestCard).not.toHaveTextContent("undefined");
    expect(ingestCard).toHaveTextContent("Queue depth");
    expect(ingestCard).toHaveTextContent("—");

    const notifCard = screen.getByText("Notification Worker").closest('[data-slot="card"]');
    if (!notifCard) throw new Error("Notification card not found");
    expect(notifCard).not.toHaveTextContent("null");
    expect(notifCard).not.toHaveTextContent("undefined");
    expect(notifCard).toHaveTextContent("Last failure reason");
    expect(notifCard).toHaveTextContent("Circuit state");
  });

  it("6-4-AC4-stale shows the stale banner with a Refresh now button that refetches", () => {
    const staleTime = Date.now() - 3 * 60_000;
    actuatorQuery = { ...actuatorQuery, dataUpdatedAt: staleTime };
    ingestQuery = { ...ingestQuery, dataUpdatedAt: staleTime };
    notifQuery = { ...notifQuery, dataUpdatedAt: staleTime };
    freshnessQuery = { ...freshnessQuery, dataUpdatedAt: staleTime };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    expect(screen.getByText(/Last updated 3 min ago/)).toBeInTheDocument();
    const refreshNow = screen.getByRole("button", { name: "Refresh now" });
    fireEvent.click(refreshNow);
    expect(actuatorQuery.refetch).toHaveBeenCalled();
    expect(ingestQuery.refetch).toHaveBeenCalled();
    expect(notifQuery.refetch).toHaveBeenCalled();
    expect(freshnessQuery.refetch).toHaveBeenCalled();
    expect(quarantineQuery.refetch).toHaveBeenCalled();
  });

  it("6-4-AC4-readonly: health cards contain no mutation controls", () => {
    render(<SystemHealthPage />, { wrapper: Wrapper });

    const healthSections = ["Dependency health", "Worker health", "Telemetry Freshness"].map((name) =>
      screen.getByRole("region", { name }),
    );
    const cards = healthSections.flatMap((section) => Array.from(section.querySelectorAll('[data-slot="card"]')));
    expect(cards.length).toBeGreaterThanOrEqual(8);
    cards.forEach((card) => {
      expect(card.querySelectorAll("button, a").length).toBe(0);
    });
  });

  it("6-4-AC5 badge exposes a non-color-only text label", () => {
    render(<SystemHealthPage />, { wrapper: Wrapper });

    const badges = screen.getAllByLabelText(/^Status: /);
    expect(badges.length).toBeGreaterThanOrEqual(8);
    badges.forEach((badge) => {
      expect(badge.textContent?.trim().length).toBeGreaterThan(0);
    });
    expect(screen.getAllByText("Up").length).toBeGreaterThanOrEqual(5);
    expect(screen.getAllByText("Running").length).toBe(2);
  });

  it("6-4-refresh: clicking the page Refresh button calls all four health refetches", () => {
    render(<SystemHealthPage />, { wrapper: Wrapper });

    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    expect(actuatorQuery.refetch).toHaveBeenCalled();
    expect(ingestQuery.refetch).toHaveBeenCalled();
    expect(notifQuery.refetch).toHaveBeenCalled();
    expect(freshnessQuery.refetch).toHaveBeenCalled();
  });

  it("6-5-AC1/AC5 LIVE shows absolute UTC and relative freshness for the latest received telemetry", () => {
    const lastAcceptedAt = new Date(Date.now() - 150_000).toISOString();
    freshnessQuery = { ...freshnessQuery, data: healthyFreshness({ lastAcceptedAt }) };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    const card = freshnessCard();
    expect(card).toHaveTextContent("Live");
    expect(card).toHaveTextContent("Latest received");
    expect(card).toHaveTextContent(formatDateTimeUtc(lastAcceptedAt));
    expect(card).toHaveTextContent(/UTC/);
    expect(card).toHaveTextContent(/\d+m ago/);
  });

  it("6-5-AC2 STALE shows the stale reason and a Stale since row", () => {
    freshnessQuery = {
      ...freshnessQuery,
      data: healthyFreshness({
        status: "STALE",
        statusLabel: "Stale",
        statusSeverity: "WARNING",
        statusReason: "No telemetry accepted since 2026-08-21T08:00:00Z",
        staleSince: "2026-08-21T08:05:00Z",
      }),
    };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    const card = freshnessCard();
    expect(card).toHaveTextContent("Stale");
    expect(card).toHaveTextContent("No telemetry accepted since 2026-08-21T08:00:00Z");
    expect(card).toHaveTextContent("Stale since");
    expect(card).toHaveTextContent(/2026/);
  });

  it("6-5-AC3 NO_DATA shows a clear empty state without claiming Live and keeps the banner non-unhealthy", () => {
    freshnessQuery = {
      ...freshnessQuery,
      data: healthyFreshness({
        status: "NO_DATA",
        statusLabel: "No data",
        statusSeverity: "NEUTRAL",
        statusReason: "No telemetry received. Verify MQTT configuration and machine setup.",
        lastAcceptedAt: null,
        staleSince: null,
      }),
    };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    const card = freshnessCard();
    expect(card).toHaveTextContent("No telemetry received. Verify MQTT configuration and machine setup.");
    expect(card).toHaveTextContent("—");
    expect(screen.getByLabelText("Status: No data")).toBeInTheDocument();
    expect(screen.queryByLabelText("Status: Live")).not.toBeInTheDocument();
    expect(screen.queryByText(/unhealthy/)).not.toBeInTheDocument();
  });

  it("6-5-banner: STALE freshness downgrades the overall banner to degraded", () => {
    freshnessQuery = {
      ...freshnessQuery,
      data: healthyFreshness({
        status: "STALE",
        statusLabel: "Stale",
        statusSeverity: "WARNING",
        statusReason: "No telemetry accepted since 2026-08-21T08:00:00Z",
        staleSince: "2026-08-21T08:05:00Z",
      }),
    };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    expect(screen.getByText(/degraded or could not be verified/)).toBeInTheDocument();
    expect(screen.queryByText(/All systems operational/)).not.toBeInTheDocument();
  });

  it("6-5-banner: NO_DATA freshness does not downgrade an otherwise healthy banner", () => {
    freshnessQuery = {
      ...freshnessQuery,
      data: healthyFreshness({
        status: "NO_DATA",
        statusLabel: "No data",
        statusSeverity: "NEUTRAL",
        statusReason: "No telemetry received. Verify MQTT configuration and machine setup.",
        lastAcceptedAt: null,
        staleSince: null,
      }),
    };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    expect(screen.getByText(/All systems operational/)).toBeInTheDocument();
  });

  it("6-5-AC4 latest received is derived from the backend lastAcceptedAt, never frontend time inference", () => {
    const { rerender } = render(<SystemHealthPage />, { wrapper: Wrapper });

    const card = freshnessCard();
    const backendTimestamp = healthyFreshness().lastAcceptedAt as string;
    expect(card).toHaveTextContent(formatDateTimeUtc(backendTimestamp));

    freshnessQuery = {
      ...freshnessQuery,
      data: healthyFreshness({
        status: "NO_DATA",
        statusLabel: "No data",
        statusSeverity: "NEUTRAL",
        statusReason: "No telemetry received. Verify MQTT configuration and machine setup.",
        lastAcceptedAt: null,
        staleSince: null,
      }),
    };
    rerender(<SystemHealthPage />);

    const noDataCard = freshnessCard();
    expect(noDataCard).toHaveTextContent("—");
    expect(noDataCard).not.toHaveTextContent(formatDateTimeUtc(backendTimestamp));
  });

  it("6-6-AC1 a DOWN dependency card shows its Next step hint while healthy cards do not", () => {
    const dbDown: ActuatorHealthComponent = {
      status: "DOWN",
      details: {
        statusLabel: "Down",
        statusSeverity: "CRITICAL",
        statusReason: "CONNECTION_FAILED",
        timestamp: "2026-08-21T10:00:00.000Z",
      },
    };
    actuatorQuery = {
      ...actuatorQuery,
      data: healthyActuator({ components: { ...healthyComponents(), db: dbDown } }),
    };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    const pgCard = screen.getByText("PostgreSQL").closest('[data-slot="card"]');
    if (!pgCard) throw new Error("PostgreSQL card not found");
    expect(pgCard).toHaveTextContent("Next step");
    expect(pgCard).toHaveTextContent("Verify the PostgreSQL service is running");

    const redisCard = screen.getByText("Redis").closest('[data-slot="card"]');
    if (!redisCard) throw new Error("Redis card not found");
    expect(redisCard).not.toHaveTextContent("Next step");
  });

  it("6-6-AC1 a DEGRADED worker card shows its Next step hint", () => {
    ingestQuery = {
      ...ingestQuery,
      data: {
        ...healthyIngest,
        status: "DEGRADED",
        statusLabel: "Degraded",
        statusSeverity: "WARNING",
        statusReason: "MQTT disconnected",
      },
    };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    const ingestCard = screen.getByText("Telemetry Ingest Worker").closest('[data-slot="card"]');
    if (!ingestCard) throw new Error("Ingest card not found");
    expect(ingestCard).toHaveTextContent("Next step");
    expect(ingestCard).toHaveTextContent("Check the ingest worker logs");
  });

  it("6-6-AC2 a failed WAHA card with lastFailedAlertId links to the failing alert's notification history", () => {
    const alertId = "00000000-0000-0000-0000-000000000042";
    const wahaDown: ActuatorHealthComponent = {
      status: "DOWN",
      details: {
        statusLabel: "Down",
        statusSeverity: "CRITICAL",
        statusReason: "WAHA unavailable",
        timestamp: "2026-08-21T10:00:00.000Z",
        state: "OPEN",
        failureRate: 0.5,
      },
    };
    actuatorQuery = {
      ...actuatorQuery,
      data: healthyActuator({ components: { ...healthyComponents(), wahaCircuitBreaker: wahaDown } }),
    };
    notifQuery = {
      ...notifQuery,
      data: { ...healthyNotif, lastFailedAlertId: alertId, recentFailedCount: 1 },
    };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    const link = screen.getByRole("link", { name: "View notification history" });
    expect(link).toHaveAttribute("href", `/dashboard/alerts/${alertId}`);

    // The link renders next to the card, never inside it (HealthCard stays read-only).
    const wahaCard = screen.getByText("WAHA").closest('[data-slot="card"]');
    if (!wahaCard) throw new Error("WAHA card not found");
    expect(wahaCard.contains(link)).toBe(false);
    expect(wahaCard).toHaveTextContent("Next step");
  });

  it("6-6-AC2 no history link when no failed alert evidence exists", () => {
    const wahaDown: ActuatorHealthComponent = {
      status: "DOWN",
      details: {
        statusLabel: "Down",
        statusSeverity: "CRITICAL",
        statusReason: "WAHA unavailable",
        timestamp: "2026-08-21T10:00:00.000Z",
        state: "OPEN",
        failureRate: 0.5,
      },
    };
    actuatorQuery = {
      ...actuatorQuery,
      data: healthyActuator({ components: { ...healthyComponents(), wahaCircuitBreaker: wahaDown } }),
    };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    expect(screen.queryByRole("link", { name: "View notification history" })).not.toBeInTheDocument();
  });

  it("6-6-AC2 a failed Notification Worker card links to the failing alert's notification history", () => {
    const alertId = "00000000-0000-0000-0000-000000000043";
    notifQuery = {
      ...notifQuery,
      data: {
        ...healthyNotif,
        status: "DEGRADED",
        statusLabel: "Degraded",
        statusSeverity: "WARNING",
        statusReason: "WAHA circuit breaker is OPEN",
        recentFailedCount: 2,
        lastFailureReason: "HTTP 500: internal error",
        lastFailedAlertId: alertId,
        circuitBreakerState: "OPEN",
      },
    };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    const link = screen.getByRole("link", { name: "View notification history" });
    expect(link).toHaveAttribute("href", `/dashboard/alerts/${alertId}`);

    const notifCard = screen.getByText("Notification Worker").closest('[data-slot="card"]');
    if (!notifCard) throw new Error("Notification card not found");
    expect(notifCard.contains(link)).toBe(false);
    expect(notifCard).toHaveTextContent("Next step");
  });

  it("6-6-AC3 shows the stale machine count row and no list when nothing is stale", () => {
    render(<SystemHealthPage />, { wrapper: Wrapper });

    const card = freshnessCard();
    expect(card).toHaveTextContent("Machines with stale telemetry");
    expect(card).toHaveTextContent("0");
    expect(screen.queryByRole("button", { name: /Show stale machines/ })).not.toBeInTheDocument();
  });

  it("6-6-AC3 stale machine evidence is collapsed by default, expands, and links to machine hubs", () => {
    staleMachinesQuery = {
      ...staleMachinesQuery,
      data: healthyStaleMachines({
        staleMachineCount: 2,
        items: [
          {
            machineId: "00000000-0000-0000-0000-000000000001",
            machineCode: "AA-01",
            plantCode: "GM1",
            freshnessState: "OFFLINE",
            statusLabel: "offline",
            lastReceivedAt: null,
          },
          {
            machineId: "00000000-0000-0000-0000-000000000002",
            machineCode: "ZZ-01",
            plantCode: "GM1",
            freshnessState: "STALE",
            statusLabel: "stale",
            lastReceivedAt: "2026-08-21T09:40:00.000Z",
          },
        ],
      }),
    };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    const card = freshnessCard();
    expect(card).toHaveTextContent("Machines with stale telemetry");
    expect(card).toHaveTextContent("2");

    const toggle = screen.getByRole("button", { name: "Show stale machines (2)" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("link", { name: "AA-01" })).not.toBeInTheDocument();

    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    const machineLink = screen.getByRole("link", { name: "AA-01" });
    expect(machineLink).toHaveAttribute("href", "/dashboard/master-data/machines/AA-01");
    expect(screen.getByRole("link", { name: "ZZ-01" })).toHaveAttribute(
      "href",
      "/dashboard/master-data/machines/ZZ-01",
    );
    expect(screen.getByText("No telemetry received")).toBeInTheDocument();
  });

  it("6-6-review: a persistently failing stale-machines query surfaces a visible error, not a silent dash", () => {
    staleMachinesQuery = { ...staleMachinesQuery, data: undefined, isError: true };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    const card = freshnessCard();
    expect(card).toHaveTextContent("Machines with stale telemetry");
    expect(card).toHaveTextContent("—");
    expect(screen.getByText("Unable to load stale machine evidence.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Show stale machines/ })).not.toBeInTheDocument();
  });

  it("6-6-review: a malformed lastFailedAlertId renders no history link", () => {
    notifQuery = {
      ...notifQuery,
      data: {
        ...healthyNotif,
        status: "DEGRADED",
        statusLabel: "Degraded",
        statusSeverity: "WARNING",
        statusReason: "WAHA circuit breaker is OPEN",
        lastFailedAlertId: "../../admin/secrets",
      },
    };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    expect(screen.queryByRole("link", { name: "View notification history" })).not.toBeInTheDocument();
  });

  it("6-6-refresh: clicking Refresh also refetches stale machines", () => {
    render(<SystemHealthPage />, { wrapper: Wrapper });

    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    expect(staleMachinesQuery.refetch).toHaveBeenCalled();
  });

  it("6-6-AC4/AC5 evidence surfaces mention no pgAdmin and expose no credentials", () => {
    const alertId = "00000000-0000-0000-0000-000000000042";
    notifQuery = {
      ...notifQuery,
      data: {
        ...healthyNotif,
        status: "DEGRADED",
        statusLabel: "Degraded",
        statusSeverity: "WARNING",
        statusReason: "WAHA circuit breaker is OPEN",
        recentFailedCount: 2,
        lastFailureReason: "HTTP 500: internal error",
        lastFailedAlertId: alertId,
        circuitBreakerState: "OPEN",
      },
    };
    staleMachinesQuery = {
      ...staleMachinesQuery,
      data: healthyStaleMachines({
        staleMachineCount: 1,
        items: [
          {
            machineId: "00000000-0000-0000-0000-000000000001",
            machineCode: "AA-01",
            plantCode: "GM1",
            freshnessState: "OFFLINE",
            statusLabel: "offline",
            lastReceivedAt: null,
          },
        ],
      }),
    };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    expect(screen.getByRole("link", { name: "View notification history" })).toBeInTheDocument();
    expect(screen.queryByText(/pgadmin/i)).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/password|token|secret|connection string/i);
  });

  it("6-7-AC1 DataQualityPanel shows all metric rows, severity text badges, and the window", () => {
    dataQualityQuery = {
      ...dataQualityQuery,
      data: healthyDataQuality({
        quarantinedCount: 25,
        rejectionRatePct: 2.5,
        anomalyCount: 3,
        quarantinedSeverity: "WARNING",
        rejectionRateSeverity: "WARNING",
        anomalySeverity: "WARNING",
        status: "DEGRADED",
        statusLabel: "Degraded",
        statusSeverity: "WARNING",
        statusReason: "rejection rate 2.5% above 1.0%",
      }),
    };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    const section = screen.getByRole("region", { name: "Data Quality" });
    expect(section).toHaveTextContent("Quarantined");
    expect(section).toHaveTextContent("25");
    expect(section).toHaveTextContent("Rejection rate");
    expect(section).toHaveTextContent("2.50%");
    expect(section).toHaveTextContent("Anomalies");
    expect(section).toHaveTextContent("3");
    expect(section).toHaveTextContent("Dead-letter");
    expect(section).toHaveTextContent("Window");
    expect(section).toHaveTextContent("last 1 hour");
    // Severity is text-labeled (never color-only): warning badges render the word.
    expect(section.textContent).toMatch(/Warning/);
    expect(section).toHaveTextContent("Degraded");
  });

  it("6-7-AC1 dead-letter above zero renders a Critical badge and overall critical state", () => {
    dataQualityQuery = {
      ...dataQualityQuery,
      data: healthyDataQuality({
        deadLetterCount: 1,
        deadLetterSeverity: "CRITICAL",
        status: "CRITICAL",
        statusLabel: "Critical",
        statusSeverity: "CRITICAL",
        statusReason: "1 dead-lettered message(s)",
      }),
    };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    const section = screen.getByRole("region", { name: "Data Quality" });
    expect(section.textContent).toMatch(/Critical/);
    expect(section).toHaveTextContent("1 dead-lettered message(s)");
  });

  it("6-7-AC4 the panel links to the quarantine log section and the anchor target exists", () => {
    render(<SystemHealthPage />, { wrapper: Wrapper });

    const link = screen.getByRole("link", { name: "View Quarantine Log" });
    expect(link).toHaveAttribute("href", "#telemetry-quarantine-log");
    expect(document.getElementById("telemetry-quarantine-log")).not.toBeNull();
  });

  it("6-7-AC2 LatencyIndicator shows the latency value and Normal state in the header", () => {
    render(<SystemHealthPage />, { wrapper: Wrapper });

    const header = screen.getByRole("heading", { name: "System Health" }).parentElement;
    if (!header) throw new Error("header not found");
    expect(header).toHaveTextContent("Latency 800 ms");
    expect(header).toHaveTextContent("Normal");
  });

  it("6-7-AC2/AC3 elevated and critical latency states are distinct and text-labeled", () => {
    dataQualityQuery = {
      ...dataQualityQuery,
      data: healthyDataQuality({
        lastLatencyMs: 6200,
        latencyState: "ELEVATED",
        latencySeverity: "WARNING",
      }),
    };
    const { unmount } = render(<SystemHealthPage />, { wrapper: Wrapper });
    let indicator = screen.getByLabelText(/Telemetry latency:/i);
    expect(indicator).toHaveTextContent("6.2s");
    expect(indicator).toHaveTextContent("Elevated");
    expect(indicator).not.toHaveTextContent("Critical");
    unmount();

    dataQualityQuery = {
      ...dataQualityQuery,
      data: healthyDataQuality({
        lastLatencyMs: 18400,
        latencyState: "CRITICAL",
        latencySeverity: "CRITICAL",
      }),
    };
    render(<SystemHealthPage />, { wrapper: Wrapper });
    indicator = screen.getByLabelText(/Telemetry latency:/i);
    expect(indicator).toHaveTextContent("18.4s");
    expect(indicator).toHaveTextContent("Critical");
  });

  it("6-7-AC2 NO_DATA latency renders No data before the first sample", () => {
    dataQualityQuery = {
      ...dataQualityQuery,
      data: healthyDataQuality({
        lastLatencyMs: null,
        latencyState: "NO_DATA",
        latencySeverity: "NEUTRAL",
      }),
    };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    const indicator = screen.getByLabelText(/Telemetry latency:/i);
    expect(indicator).toHaveTextContent("No data");
  });

  it("6-7-error: a failing data-quality query surfaces a visible error without breaking the page", () => {
    dataQualityQuery = {
      ...dataQualityQuery,
      data: undefined,
      isError: true,
    };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    expect(screen.getByText("Unable to load data quality.")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "System Health" })).toBeInTheDocument();
    expect(screen.getByText("PostgreSQL")).toBeInTheDocument();
  });

  it("6-7-loading: a loading data-quality query renders skeletons instead of metric rows", () => {
    dataQualityQuery = {
      ...dataQualityQuery,
      data: undefined,
      isLoading: true,
    };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    expect(screen.queryByText("Quarantined")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "System Health" })).toBeInTheDocument();
  });

  it("6-7-refresh: clicking Refresh also refetches data quality", () => {
    render(<SystemHealthPage />, { wrapper: Wrapper });

    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    expect(dataQualityQuery.refetch).toHaveBeenCalled();
  });

  it("6-4-forbidden: non-SUPER_ADMIN sees Permission denied and no dashboard content", () => {
    mockUser = { id: "u-1", loginIdentifier: "user@test.com", applicationRole: "MANAGER_MAINTENANCE" };

    render(
      <RoleGuard allowedRoles={["SUPER_ADMIN"]} title="System Health">
        <SystemHealthPage />
      </RoleGuard>,
      { wrapper: Wrapper },
    );

    expect(screen.getByText("Permission denied")).toBeInTheDocument();
    expect(screen.queryByText("Dependencies")).not.toBeInTheDocument();
  });
});

describe("computeOverallBanner", () => {
  const base = {
    actuatorLoading: false,
    actuatorError: false,
    components: healthyComponents(),
    ingestLoading: false,
    ingestError: false,
    ingestSeverity: "success" as const,
    notifLoading: false,
    notifError: false,
    notifSeverity: "success" as const,
  };

  it("is healthy when every resolved source is UP/RUNNING", () => {
    expect(computeOverallBanner(base)).toBe("healthy");
  });

  it("is null while any source is still loading", () => {
    expect(computeOverallBanner({ ...base, ingestLoading: true })).toBeNull();
  });

  it("is unhealthy when a dependency is DOWN", () => {
    const components = {
      ...healthyComponents(),
      db: { status: "DOWN" as const, details: { statusLabel: "Down", statusSeverity: "CRITICAL" } },
    };
    expect(computeOverallBanner({ ...base, components })).toBe("unhealthy");
  });

  it("is unhealthy when a worker is STOPPED", () => {
    expect(computeOverallBanner({ ...base, notifSeverity: "critical" })).toBe("unhealthy");
  });

  it("is degraded (not unhealthy) when a worker is DEGRADED", () => {
    expect(computeOverallBanner({ ...base, ingestSeverity: "warning" })).toBe("degraded");
  });

  it("is degraded (not unhealthy) when a dependency is OUT_OF_SERVICE", () => {
    const components = {
      ...healthyComponents(),
      mqtt: {
        status: "OUT_OF_SERVICE" as const,
        details: { statusLabel: "Out of service", statusSeverity: "WARNING" },
      },
    };
    expect(computeOverallBanner({ ...base, components })).toBe("degraded");
  });

  it("is degraded (not unhealthy) when dependency data is absent", () => {
    expect(computeOverallBanner({ ...base, components: {} })).toBe("degraded");
  });

  it("is degraded when a source errored while others are healthy", () => {
    expect(computeOverallBanner({ ...base, ingestError: true })).toBe("degraded");
  });

  it("is degraded when all sources errored", () => {
    expect(computeOverallBanner({ ...base, actuatorError: true, ingestError: true, notifError: true })).toBe(
      "degraded",
    );
  });

  it("is degraded when a worker severity is unverifiable (no data, no error)", () => {
    expect(computeOverallBanner({ ...base, ingestSeverity: "unknown" })).toBe("degraded");
  });

  it("uses the enriched severity for the banner even when it disagrees with the coarse status", () => {
    const components = {
      ...healthyComponents(),
      db: { status: "UP" as const, details: { statusLabel: "Up", statusSeverity: "CRITICAL" } },
    };
    expect(computeOverallBanner({ ...base, components })).toBe("unhealthy");
  });

  it("stays healthy when coarse status is DOWN but enriched severity is SUCCESS (label fallback)", () => {
    const components = {
      ...healthyComponents(),
      redis: { status: "DOWN" as const, details: { statusLabel: "Running fine", statusSeverity: "SUCCESS" } },
    };
    expect(computeOverallBanner({ ...base, components })).toBe("healthy");
  });

  it("is degraded when freshness is STALE (warning)", () => {
    expect(computeOverallBanner({ ...base, freshnessSeverity: "warning" })).toBe("degraded");
  });

  it("is degraded when freshness errored while everything else is healthy", () => {
    expect(computeOverallBanner({ ...base, freshnessError: true })).toBe("degraded");
  });

  it("is null while freshness is still loading", () => {
    expect(computeOverallBanner({ ...base, freshnessLoading: true })).toBeNull();
  });

  it("is healthy when freshness is LIVE (success)", () => {
    expect(computeOverallBanner({ ...base, freshnessSeverity: "success" })).toBe("healthy");
  });

  it("is healthy when freshness is NO_DATA (excluded from the banner)", () => {
    expect(computeOverallBanner({ ...base, freshnessSeverity: undefined })).toBe("healthy");
  });
});

describe("formatRelativeFreshness", () => {
  const NOW = new Date("2026-08-21T12:00:00.000Z").getTime();

  it("renders seconds ago under a minute", () => {
    expect(formatRelativeFreshness("2026-08-21T11:59:58.000Z", NOW)).toBe("2s ago");
  });

  it("renders just now for a sub-second diff (0s boundary)", () => {
    expect(formatRelativeFreshness("2026-08-21T12:00:00.000Z", NOW)).toBe("just now");
  });

  it("renders 59s ago at the seconds boundary", () => {
    expect(formatRelativeFreshness("2026-08-21T11:59:01.000Z", NOW)).toBe("59s ago");
  });

  it("renders minutes ago from 60s", () => {
    expect(formatRelativeFreshness("2026-08-21T11:59:00.000Z", NOW)).toBe("1m ago");
  });

  it("renders 59m ago at the minutes boundary", () => {
    expect(formatRelativeFreshness("2026-08-21T11:01:00.000Z", NOW)).toBe("59m ago");
  });

  it("renders hours ago from 60m", () => {
    expect(formatRelativeFreshness("2026-08-21T11:00:00.000Z", NOW)).toBe("1h ago");
  });

  it("renders 23h ago at the hours boundary", () => {
    expect(formatRelativeFreshness("2026-08-21T12:01:00.000Z", new Date("2026-08-22T12:00:00.000Z").getTime())).toBe(
      "23h ago",
    );
  });

  it("renders days ago from 24h", () => {
    expect(formatRelativeFreshness("2026-08-21T11:59:00.000Z", new Date("2026-08-22T12:00:00.000Z").getTime())).toBe(
      "1d ago",
    );
  });

  it("returns a dash for an invalid timestamp", () => {
    expect(formatRelativeFreshness("not-a-date", NOW)).toBe("—");
  });

  it("returns just now for a future timestamp (clock skew)", () => {
    expect(formatRelativeFreshness("2026-08-21T12:05:00.000Z", NOW)).toBe("just now");
  });
});
