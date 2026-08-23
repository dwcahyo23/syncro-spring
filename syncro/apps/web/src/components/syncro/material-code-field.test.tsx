import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { MaterialCodeField } from "./material-code-field";

describe("MaterialCodeField", () => {
  it("renders the value and emits changes", () => {
    const onChange = vi.fn();
    render(<MaterialCodeField value="MC-001" onChange={onChange} />);

    const input = screen.getByTestId("material-code-input");
    expect(input).toHaveValue("MC-001");

    fireEvent.change(input, { target: { value: "MC-002" } });
    expect(onChange).toHaveBeenCalledWith("MC-002");
  });

  it("shows the error message with alert role when provided", () => {
    render(<MaterialCodeField value="" onChange={() => undefined} error="Already used by another sparepart." />);

    expect(screen.getByRole("alert")).toHaveTextContent("Already used by another sparepart.");
    expect(screen.getByTestId("material-code-input")).toBeInvalid();
  });

  it("disables the input in read-only mode and shows the disabled reason instead of the hint", () => {
    render(<MaterialCodeField value="MC-LOCKED" onChange={() => undefined} readOnly disabledReason="View only." />);

    const input = screen.getByTestId("material-code-input");
    expect(input).toBeDisabled();
    expect(screen.getByText("View only.")).toBeInTheDocument();
  });

  it("caps input length at 64 characters", () => {
    render(<MaterialCodeField value={Array.from({ length: 64 }, () => "a").join("")} onChange={() => undefined} />);
    expect(screen.getByTestId("material-code-input")).toHaveProperty("maxLength", 64);
  });
});
