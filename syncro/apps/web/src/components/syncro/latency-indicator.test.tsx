import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { formatLatencyLabel, LatencyIndicator } from "./latency-indicator";

describe("formatLatencyLabel", () => {
  it("returns 'No data' for a missing sample", () => {
    expect(formatLatencyLabel(null)).toBe("No data");
  });

  it("renders milliseconds below one second", () => {
    expect(formatLatencyLabel(0)).toBe("0 ms");
    expect(formatLatencyLabel(999)).toBe("999 ms");
  });

  it("renders seconds with one decimal at and above one second", () => {
    expect(formatLatencyLabel(1000)).toBe("1.0s");
    expect(formatLatencyLabel(15_499)).toBe("15.5s");
  });
});

describe("LatencyIndicator", () => {
  it("renders the value and backend state verbatim", () => {
    render(<LatencyIndicator latencyState="NORMAL" lastLatencyMs={800} />);

    expect(screen.getByLabelText("Telemetry latency: 800 ms, Normal")).toBeInTheDocument();
  });

  it("does not duplicate the No data label for NO_DATA", () => {
    render(<LatencyIndicator latencyState="NO_DATA" lastLatencyMs={null} />);

    const indicator = screen.getByLabelText("Telemetry latency: No data");
    expect(indicator).toHaveTextContent("Latency No data");
    expect(indicator.textContent?.match(/No data/g)).toHaveLength(1);
  });

  it("shows unavailable alone when the first fetch fails (no contradictory state label)", () => {
    render(<LatencyIndicator latencyState={undefined} lastLatencyMs={undefined} isError={true} />);

    const indicator = screen.getByLabelText("Telemetry latency: unavailable");
    expect(indicator).toHaveTextContent("Latency unavailable");
    expect(indicator).not.toHaveTextContent("No data");
  });

  it("marks the shown value as last known when a refresh fails with data present", () => {
    render(<LatencyIndicator latencyState="NORMAL" lastLatencyMs={800} isError={true} />);

    expect(screen.getByText("(last known — refresh failed)")).toBeInTheDocument();
  });

  it("renders a skeleton while loading", () => {
    render(<LatencyIndicator latencyState={undefined} lastLatencyMs={undefined} isLoading={true} />);

    expect(screen.getByRole("status", { name: "Telemetry latency" })).toBeInTheDocument();
  });
});
