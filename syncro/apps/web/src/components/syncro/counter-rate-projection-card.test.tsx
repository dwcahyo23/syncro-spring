import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { InstallationProjection, MachineSparepartProjectionsView } from "@/lib/api/generated/model";

import { CounterRateProjectionView } from "./counter-rate-projection-card";

function projection(overrides: Partial<InstallationProjection> = {}): InstallationProjection {
  return {
    installationId: "inst-1",
    sparepartId: "sp-1",
    functionName: "Primary feeder",
    available: true,
    reason: undefined,
    remainingCounters: 2700,
    projectedDepletionAt: "2026-09-05T10:00:00Z",
    leadTimeHours: 36.5,
    consumptionDuringLeadTime: 548,
    ...overrides,
  };
}

function view(overrides: Partial<MachineSparepartProjectionsView> = {}): MachineSparepartProjectionsView {
  return {
    machineId: "m-1",
    rateAvailable: true,
    calculationBasis: "ROLLING_30_DAY",
    windowStartAt: "2026-07-25T10:00:00Z",
    windowEndAt: "2026-08-24T10:00:00Z",
    firstSampleAt: "2026-07-24T23:30:00Z",
    lastSampleAt: "2026-08-24T09:40:00Z",
    insufficientReason: undefined,
    ratePerOperatingHour: 15,
    shiftSource: "MACHINE",
    dailyOperatingHours: 15,
    projections: [projection()],
    ...overrides,
  };
}

describe("CounterRateProjectionView", () => {
  it("renders the rate tile with unit, basis badge and window evidence", () => {
    render(<CounterRateProjectionView data={view()} />);

    expect(screen.getByText("Counter Rate & Depletion")).toBeInTheDocument();
    expect(screen.getByText("counters/op-hour")).toBeInTheDocument();
    expect(screen.getByText("Rolling 30 days")).toBeInTheDocument();
    expect(screen.getByText("Jul 25, 2026, 10:00 AM UTC")).toBeInTheDocument();
    expect(screen.getByText("Operating time")).toBeInTheDocument();
    expect(screen.getByText("15 h/day (MACHINE schedule)")).toBeInTheDocument();
  });

  it("renders per-installation rows with remaining counters, depletion date and lead-time consumption", () => {
    render(<CounterRateProjectionView data={view()} />);

    expect(screen.getByText("Primary feeder")).toBeInTheDocument();
    expect(screen.getByText("Remaining counters")).toBeInTheDocument();
    expect(screen.getByText("2,700")).toBeInTheDocument();
    expect(screen.getByText("Lead-time consumption")).toBeInTheDocument();
    expect(screen.getByText(/≈548 counters during 36.5 op-hour lead time/)).toBeInTheDocument();
  });

  it("renders the FULL_HISTORY fallback basis label", () => {
    render(<CounterRateProjectionView data={view({ calculationBasis: "FULL_HISTORY" })} />);

    expect(screen.getByText("Full history")).toBeInTheDocument();
  });

  it("shows explicit human text for each backend insufficient reason without a guessed rate", () => {
    const { rerender } = render(
      <CounterRateProjectionView
        data={view({
          rateAvailable: false,
          calculationBasis: undefined,
          ratePerOperatingHour: undefined,
          insufficientReason: "NO_TELEMETRY",
          projections: [],
        })}
      />,
    );

    expect(screen.getByText("No accepted telemetry in the estimation window.")).toBeInTheDocument();

    rerender(
      <CounterRateProjectionView
        data={view({
          rateAvailable: false,
          calculationBasis: undefined,
          ratePerOperatingHour: undefined,
          insufficientReason: "STALE_DATA",
          projections: [],
        })}
      />,
    );
    expect(screen.getByText("Latest telemetry is too old to estimate a current rate.")).toBeInTheDocument();

    rerender(
      <CounterRateProjectionView
        data={view({
          rateAvailable: false,
          calculationBasis: undefined,
          ratePerOperatingHour: undefined,
          insufficientReason: "NO_OPERATING_TIME",
          projections: [],
        })}
      />,
    );
    expect(screen.getByText("No shift schedule configured, so operating hours are zero.")).toBeInTheDocument();
  });

  it("degrades an unavailable installation row with its own reason and hides projection numbers", () => {
    render(
      <CounterRateProjectionView
        data={{
          ...view(),
          projections: [
            projection({
              available: false,
              reason: "INSUFFICIENT_SAMPLES",
              remainingCounters: undefined,
              projectedDepletionAt: undefined,
              consumptionDuringLeadTime: undefined,
            }),
          ],
        }}
      />,
    );

    expect(screen.getAllByText("Not enough telemetry samples to estimate a rate.")).toHaveLength(1);
    expect(screen.queryByText("Projected")).toBeNull();
    expect(screen.getByText("Unavailable")).toBeInTheDocument();
  });

  it("renders a depleted installation whose projected depletion is now", () => {
    render(
      <CounterRateProjectionView
        data={view({
          projections: [projection({ remainingCounters: 0, projectedDepletionAt: "2026-08-24T10:00:00Z" })],
        })}
      />,
    );

    expect(screen.getByText("0")).toBeInTheDocument();
  });

  it("omits the lead-time line for spareparts without lead time", () => {
    render(
      <CounterRateProjectionView
        data={view({
          projections: [projection({ leadTimeHours: undefined, consumptionDuringLeadTime: undefined })],
        })}
      />,
    );

    expect(screen.queryByText("Lead-time consumption")).toBeNull();
  });

  it("renders a loading skeleton without content", () => {
    render(<CounterRateProjectionView isLoading />);

    expect(document.querySelector("[aria-hidden='true'] .animate-pulse")).toBeTruthy();
    expect(screen.queryByText("counters/op-hour")).toBeNull();
  });

  it("keeps last known data visible with a notice on refresh failure", () => {
    render(<CounterRateProjectionView data={view()} error />);

    expect(screen.getByText("Unable to refresh projections. Showing last known estimates.")).toBeInTheDocument();
    expect(screen.getByText("counters/op-hour")).toBeInTheDocument();
  });

  it("renders a load failure message when no data exists", () => {
    render(<CounterRateProjectionView error />);

    expect(screen.getByText("Unable to load projections.")).toBeInTheDocument();
  });
});
