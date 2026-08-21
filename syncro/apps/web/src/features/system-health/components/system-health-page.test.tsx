import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { RoleGuard } from "@/components/syncro/role-guard";
import type {
  ActuatorHealthComponent,
  ActuatorHealthResponse,
  IngestWorkerStatus,
  NotificationWorkerStatus,
} from "@/features/system-health/types";

import { computeOverallBanner, SystemHealthPage } from "./system-health-page";

// ─── Mock modules ───────────────────────────────────────────────────────────────

let actuatorQuery: HealthQueryState;
let ingestQuery: WorkerQueryState;
let notifQuery: WorkerQueryState;
let quarantineQuery: QuarantineQueryState;

vi.mock("@/features/system-health/hooks/use-actuator-health-query", () => ({
  SYSTEM_HEALTH_REFRESH_INTERVAL_MS: 30_000,
  useActuatorHealthQuery: () => actuatorQuery,
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
  lastSuccessfulSendAt: "2026-08-21T09:55:00.000Z",
  circuitBreakerState: "CLOSED",
};

// ─── Test wrapper ───────────────────────────────────────────────────────────────

const queryClient = new QueryClient();

const Wrapper = ({ children }: { children: React.ReactNode }) => (
  <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
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
  });

  it("6-4-AC1 renders all seven cards: PostgreSQL, InfluxDB, Redis, MQTT, WAHA, Telemetry Ingest Worker, Notification Worker", () => {
    render(<SystemHealthPage />, { wrapper: Wrapper });

    expect(screen.getByText("PostgreSQL")).toBeInTheDocument();
    expect(screen.getByText("InfluxDB")).toBeInTheDocument();
    expect(screen.getByText("Redis")).toBeInTheDocument();
    expect(screen.getByText("MQTT / EMQX")).toBeInTheDocument();
    expect(screen.getByText("WAHA")).toBeInTheDocument();
    expect(screen.getByText("Telemetry Ingest Worker")).toBeInTheDocument();
    expect(screen.getByText("Notification Worker")).toBeInTheDocument();
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

    render(<SystemHealthPage />, { wrapper: Wrapper });

    const skeletons = document.querySelectorAll('[data-slot="skeleton"]');
    expect(skeletons.length).toBeGreaterThan(0);
    expect(screen.queryByText("All systems operational.")).not.toBeInTheDocument();
  });

  it("6-4-AC4-error renders per-card Unable to check fallback", () => {
    actuatorQuery = { ...actuatorQuery, isError: true, data: undefined as unknown as ActuatorHealthResponse };
    ingestQuery = { ...ingestQuery, isError: true, data: undefined };
    notifQuery = { ...notifQuery, isError: true, data: undefined };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    expect(screen.getByText("Unable to check PostgreSQL.")).toBeInTheDocument();
    expect(screen.getByText("Unable to check InfluxDB.")).toBeInTheDocument();
    expect(screen.getByText("Unable to check Redis.")).toBeInTheDocument();
    expect(screen.getByText("Unable to check MQTT / EMQX.")).toBeInTheDocument();
    expect(screen.getByText("Unable to check WAHA.")).toBeInTheDocument();
    expect(screen.getByText("Unable to check Telemetry Ingest Worker.")).toBeInTheDocument();
    expect(screen.getByText("Unable to check Notification Worker.")).toBeInTheDocument();
    expect(screen.getByText(/degraded or could not be verified/)).toBeInTheDocument();
  });

  it("6-4-AC4-empty renders No health data reported", () => {
    actuatorQuery = {
      ...actuatorQuery,
      data: { status: "UP", components: {} },
    };
    ingestQuery = { ...ingestQuery, data: undefined };
    notifQuery = { ...notifQuery, data: undefined };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    const emptyMessages = screen.getAllByText("No health data reported.");
    expect(emptyMessages.length).toBe(7);
  });

  it("shows last known status (not just the error text) when a refetch fails with cached data", () => {
    ingestQuery = { ...ingestQuery, isError: true };
    notifQuery = { ...notifQuery, isError: true };

    render(<SystemHealthPage />, { wrapper: Wrapper });

    expect(screen.getByText(/Unable to refresh Telemetry Ingest Worker/)).toBeInTheDocument();
    expect(screen.getByText(/Unable to refresh Notification Worker/)).toBeInTheDocument();
    expect(screen.getAllByText("Running").length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText("42")).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();
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

    render(<SystemHealthPage />, { wrapper: Wrapper });

    expect(screen.getByText(/Last updated 3 min ago/)).toBeInTheDocument();
    const refreshNow = screen.getByRole("button", { name: "Refresh now" });
    fireEvent.click(refreshNow);
    expect(actuatorQuery.refetch).toHaveBeenCalled();
    expect(ingestQuery.refetch).toHaveBeenCalled();
    expect(notifQuery.refetch).toHaveBeenCalled();
    expect(quarantineQuery.refetch).toHaveBeenCalled();
  });

  it("6-4-AC4-readonly: health cards contain no mutation controls", () => {
    render(<SystemHealthPage />, { wrapper: Wrapper });

    const healthSections = ["Dependency health", "Worker health"].map((name) => screen.getByRole("region", { name }));
    const cards = healthSections.flatMap((section) => Array.from(section.querySelectorAll('[data-slot="card"]')));
    expect(cards.length).toBeGreaterThanOrEqual(7);
    cards.forEach((card) => {
      expect(card.querySelectorAll("button, a").length).toBe(0);
    });
  });

  it("6-4-AC5 badge exposes a non-color-only text label", () => {
    render(<SystemHealthPage />, { wrapper: Wrapper });

    const badges = screen.getAllByLabelText(/^Status: /);
    expect(badges.length).toBeGreaterThanOrEqual(7);
    badges.forEach((badge) => {
      expect(badge.textContent?.trim().length).toBeGreaterThan(0);
    });
    expect(screen.getAllByText("Up").length).toBeGreaterThanOrEqual(5);
    expect(screen.getAllByText("Running").length).toBe(2);
  });

  it("6-4-refresh: clicking the page Refresh button calls all three health refetches", () => {
    render(<SystemHealthPage />, { wrapper: Wrapper });

    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    expect(actuatorQuery.refetch).toHaveBeenCalled();
    expect(ingestQuery.refetch).toHaveBeenCalled();
    expect(notifQuery.refetch).toHaveBeenCalled();
  });

  it("6-4-forbidden: non-SUPER_ADMIN sees Permission denied and no dashboard content", () => {
    mockUser = { id: "u-1", loginIdentifier: "user@test.com", applicationRole: "MANAGE" };

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
});
