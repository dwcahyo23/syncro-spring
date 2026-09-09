import type { ReactNode } from "react";

import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { renderI18n } from "@/test/i18n-wrapper";

import { deriveSeverity, HealthCard, HealthMetricRow } from "./health-card";

describe("HealthCard", () => {
  it("renders title, status label, severity, reason, and timestamp", () => {
    renderI18n(
      <HealthCard
        title="PostgreSQL"
        description="Primary relational store"
        statusLabel="Down"
        statusSeverity="CRITICAL"
        statusReason="CONNECTION_FAILED"
        timestamp="2026-08-21T10:00:00.000Z"
      />,
    );

    expect(screen.getByText("PostgreSQL")).toBeInTheDocument();
    expect(screen.getByText("Primary relational store")).toBeInTheDocument();
    expect(screen.getByText("Down")).toBeInTheDocument();
    expect(screen.getByText("Critical")).toBeInTheDocument();
    expect(screen.getByText("CONNECTION_FAILED")).toBeInTheDocument();
    expect(screen.getByText(/2026/)).toBeInTheDocument();
    expect(screen.getByText(/UTC/)).toBeInTheDocument();
  });

  it("renders extra metric rows passed as children", () => {
    renderI18n(
      <HealthCard title="Telemetry Ingest Worker" statusLabel="Running" statusSeverity="SUCCESS">
        <div>MQTT state</div>
        <div>Queue depth</div>
      </HealthCard>,
    );

    expect(screen.getByText("MQTT state")).toBeInTheDocument();
    expect(screen.getByText("Queue depth")).toBeInTheDocument();
  });

  it("renders the error fallback text", () => {
    renderI18n(<HealthCard title="Redis" error />);
    expect(screen.getByText("Unable to check Redis.")).toBeInTheDocument();
  });

  it("renders the empty fallback text", () => {
    renderI18n(<HealthCard title="Redis" empty />);
    expect(screen.getByText("No health data reported.")).toBeInTheDocument();
  });

  it("renders a skeleton while loading", () => {
    renderI18n(<HealthCard title="Redis" loading />);
    const skeletons = document.querySelectorAll('[data-slot="skeleton"]');
    expect(skeletons.length).toBeGreaterThan(0);
    expect(screen.queryByText("No health data reported.")).not.toBeInTheDocument();
  });

  it("badge exposes a non-color-only text label via aria-label", () => {
    renderI18n(<HealthCard title="MQTT / EMQX" statusLabel="Down" statusSeverity="CRITICAL" />);

    const badge = screen.getByLabelText("Status: Down");
    expect(badge).toBeInTheDocument();
    expect(badge.getAttribute("aria-label")).toContain("Status:");
    expect(badge).toHaveTextContent("Down");
  });

  it("derives severity from the status label when not provided", () => {
    expect(deriveSeverity("Down")).toBe("CRITICAL");
    expect(deriveSeverity("Stopped")).toBe("CRITICAL");
    expect(deriveSeverity("Failed")).toBe("CRITICAL");
    expect(deriveSeverity("Out of service")).toBe("CRITICAL");
    expect(deriveSeverity("Degraded")).toBe("WARNING");
    expect(deriveSeverity("Stale")).toBe("WARNING");
    expect(deriveSeverity("Disconnected")).toBe("WARNING");
    expect(deriveSeverity("Up")).toBe("SUCCESS");
    expect(deriveSeverity("Running")).toBe("SUCCESS");
    expect(deriveSeverity("Unknown")).toBe("NEUTRAL");
    expect(deriveSeverity(undefined)).toBe("NEUTRAL");
  });

  it("does not misclassify negated phrases", () => {
    expect(deriveSeverity("Not failed")).toBe("NEUTRAL");
    expect(deriveSeverity("Not degraded")).toBe("NEUTRAL");
    expect(deriveSeverity("Not down")).toBe("NEUTRAL");
    expect(deriveSeverity("Not stopped")).toBe("NEUTRAL");
    expect(deriveSeverity("Not connected")).toBe("WARNING");
    expect(deriveSeverity("Not running")).toBe("WARNING");
  });

  it("renders the error fallback alongside last known data when data is present", () => {
    renderI18n(
      <HealthCard
        title="Telemetry Ingest Worker"
        statusLabel="Running"
        statusSeverity="SUCCESS"
        timestamp="2026-08-21T10:00:00.000Z"
        error
      >
        <div>MQTT state</div>
      </HealthCard>,
    );

    expect(screen.getByText(/Unable to refresh Telemetry Ingest Worker/)).toBeInTheDocument();
    expect(screen.getByText("Running")).toBeInTheDocument();
    expect(screen.getByText("MQTT state")).toBeInTheDocument();
  });

  it("keeps labels intact without altering acronym casing", () => {
    renderI18n(
      <HealthCard title="MQTT / EMQX" statusLabel="Up" statusSeverity="SUCCESS">
        <HealthMetricRow label="MQTT state" value="SUBSCRIBED" />
        <HealthMetricRow label="WAHA circuit" value="CLOSED" />
      </HealthCard>,
    );

    expect(screen.getByText("MQTT state")).toBeInTheDocument();
    expect(screen.getByText("WAHA circuit")).toBeInTheDocument();
  });

  it("badge falls back to Unknown label when no status label is provided", () => {
    renderI18n(<HealthCard title="Redis" />);
    expect(screen.getByLabelText("Status: Unknown")).toBeInTheDocument();
  });
});
