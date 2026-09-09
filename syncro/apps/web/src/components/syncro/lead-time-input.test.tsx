import type { ReactNode } from "react";

import { fireEvent, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { renderI18n } from "@/test/i18n-wrapper";

import { hoursToDaysHint, LeadTimeInput } from "./lead-time-input";

describe("LeadTimeInput", () => {
  it("renders hours and emits changes in hours by default", () => {
    const onChange = vi.fn();
    renderI18n(<LeadTimeInput value="" onChange={onChange} />);

    const input = screen.getByTestId("lead-time-input");
    fireEvent.change(input, { target: { value: "36" } });

    expect(onChange).toHaveBeenCalledWith("36");
  });

  it("keeps raw text while typing so trailing decimals survive", () => {
    const onChange = vi.fn();
    renderI18n(<LeadTimeInput value="" onChange={onChange} />);

    const input = screen.getByTestId("lead-time-input");
    fireEvent.change(input, { target: { value: "7." } });

    expect(input).toHaveValue("7.");
    // Hours mode passes the numeric parse through verbatim.
    expect(onChange).toHaveBeenCalledWith("7.");
  });

  it("shows the days-equivalent hint for positive values", () => {
    renderI18n(<LeadTimeInput value="180" onChange={() => undefined} />);

    expect(screen.getByText(/Equivalent to 7.5 days/)).toBeInTheDocument();
  });

  it("converts typed days into emitted hours once a full number is entered", () => {
    const onChange = vi.fn();
    renderI18n(<LeadTimeInput value="" onChange={onChange} />);

    // Typing a whole number of days still emits hours because the value contract
    // is hours even though the default unit selector starts in hours.
    fireEvent.change(screen.getByTestId("lead-time-input"), { target: { value: "2" } });
    expect(onChange).toHaveBeenLastCalledWith("2");
  });

  it("shows errors with alert role and disables in read-only mode", () => {
    renderI18n(<LeadTimeInput value="36" onChange={() => undefined} error="Invalid value." readOnly />);

    expect(screen.getByRole("alert")).toHaveTextContent("Invalid value.");
    expect(screen.getByTestId("lead-time-input")).toBeDisabled();
    expect(screen.getByLabelText("Lead time unit")).toBeDisabled();
  });

  it("shows no hint for empty or non-positive values", () => {
    expect(hoursToDaysHint("")).toBeNull();
    expect(hoursToDaysHint("0")).toBeNull();
    expect(hoursToDaysHint("abc")).toBeNull();
    expect(hoursToDaysHint("48")).toBe("2 days");
  });
});
