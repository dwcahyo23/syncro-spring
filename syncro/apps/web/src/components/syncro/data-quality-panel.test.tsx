import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { TelemetryDataQualityStatus } from "@/features/system-health/types";

import { DataQualityPanel, formatWindowLabel } from "./data-quality-panel";

function healthyStatus(
  overrides: Partial<TelemetryDataQualityStatus> = {},
): TelemetryDataQualityStatus {
  return {
    status: "GOOD",
    statusLabel: "Good",
    statusSeverity: "SUCCESS",
    statusReason: null,
    timestamp: "2026-08-22T10:00:00Z",
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

describe("formatWindowLabel", () => {
  it("renders the default 1-hour window as 'last 1 hour'", () => {
    expect(formatWindowLabel(3600)).toBe("last 1 hour");
  });

  it("renders non-default windows in minutes per the page-spec contract", () => {
    expect(formatWindowLabel(7200)).toBe("last 120 minutes");
    expect(formatWindowLabel(180)).toBe("last 3 minutes");
    expect(formatWindowLabel(60)).toBe("last 1 minute");
  });
});

describe("DataQualityPanel", () => {
  it("renders all metric rows with text badges and the window", () => {
    render(<DataQualityPanel status={healthyStatus()} isLoading={false} isError={false} />);

    expect(screen.getByText("Quarantined")).toBeInTheDocument();
    expect(screen.getByText("Rejection rate")).toBeInTheDocument();
    expect(screen.getByText("Anomalies")).toBeInTheDocument();
    expect(screen.getByText("Dead-letter")).toBeInTheDocument();
    expect(screen.getByText("last 1 hour")).toBeInTheDocument();
    // Four metric badges plus the overall badge all render the OK text label.
    expect(screen.getAllByText("OK")).toHaveLength(5);
    expect(screen.getByText("View Quarantine Log")).toBeInTheDocument();
  });

  it("renders the all-zero state without error text", () => {
    render(
      <DataQualityPanel
        status={healthyStatus({
          quarantinedCount: 0,
          rejectionRatePct: 0,
          receivedCount: 0,
          lastLatencyMs: null,
          latencyState: "NO_DATA",
          latencySeverity: "NEUTRAL",
        })}
        isLoading={false}
        isError={false}
      />,
    );

    expect(screen.getByText("0.00%")).toBeInTheDocument();
    expect(screen.getByText("last 1 hour")).toBeInTheDocument();
    expect(screen.queryByText(/unable/i)).not.toBeInTheDocument();
  });

  it("keeps last known metrics visible with a notice when a refresh fails", () => {
    render(<DataQualityPanel status={healthyStatus()} isLoading={false} isError={true} />);

    expect(screen.getByText("Unable to refresh data quality. Showing last known metrics.")).toBeInTheDocument();
    expect(screen.getByText("Quarantined")).toBeInTheDocument();
  });

  it("renders 'Unable to load data quality.' when the first load fails", () => {
    render(<DataQualityPanel status={undefined} isLoading={false} isError={true} />);

    expect(screen.getByText("Unable to load data quality.")).toBeInTheDocument();
  });

  it("falls back to the Neutral badge for an unexpected severity without crashing", () => {
    // Defense in depth behind the fetcher guard: a proto-key or unknown severity string
    // must render a text-labeled Neutral badge, never an undefined icon/label crash.
    render(
      <DataQualityPanel
        status={healthyStatus({ quarantinedSeverity: "toString" })}
        isLoading={false}
        isError={false}
      />,
    );

    expect(screen.getByText("Neutral")).toBeInTheDocument();
    expect(screen.getByText("Quarantined")).toBeInTheDocument();
  });
});
