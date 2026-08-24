import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { TooltipProvider } from "@/components/ui/tooltip";

import { AlertTypeBadge } from "./alert-type-badge";

const Wrapper = ({ children }: { children: React.ReactNode }) => <TooltipProvider>{children}</TooltipProvider>;

describe("AlertTypeBadge", () => {
  it("renders the Threshold label for THRESHOLD_PERCENTAGE", () => {
    render(
      <Wrapper>
        <AlertTypeBadge alertType="THRESHOLD_PERCENTAGE" />
      </Wrapper>,
    );

    expect(screen.getByText("Threshold")).toBeInTheDocument();
    expect(
      screen.getByLabelText("Alert type: Threshold. Consumed lifetime reached the configured percentage threshold."),
    ).toBeInTheDocument();
  });

  it("renders the Procurement risk label for PROCUREMENT_RISK", () => {
    render(
      <Wrapper>
        <AlertTypeBadge alertType="PROCUREMENT_RISK" />
      </Wrapper>,
    );

    expect(screen.getByText("Procurement risk")).toBeInTheDocument();
    expect(
      screen.getByLabelText(
        "Alert type: Procurement risk. Projected depletion falls within the sparepart lead-time window.",
      ),
    ).toBeInTheDocument();
  });

  it("renders an explicit Unknown label for undefined alertType instead of defaulting to Threshold", () => {
    render(
      <Wrapper>
        <AlertTypeBadge alertType={undefined} />
      </Wrapper>,
    );

    expect(screen.getByText("Unknown")).toBeInTheDocument();
    expect(screen.getByLabelText("Alert type: Unknown. Alert type is not recognized.")).toBeInTheDocument();
  });

  it("renders an explicit Unknown label for an unrecognized alertType", () => {
    render(
      <Wrapper>
        <AlertTypeBadge alertType={"SOMETHING_NEW" as never} />
      </Wrapper>,
    );

    expect(screen.getByText("Unknown")).toBeInTheDocument();
  });
});
