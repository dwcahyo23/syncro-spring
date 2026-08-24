import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { ShiftConfigEditor } from "./shift-config-editor";

describe("ShiftConfigEditor", () => {
  it("renders one row per configured window with time inputs", () => {
    render(
      <ShiftConfigEditor
        value={[
          { startTime: "07:00", endTime: "15:00" },
          { startTime: "23:00", endTime: "06:00" },
        ]}
        onChange={vi.fn()}
      />,
    );

    expect(screen.getByLabelText("Shift 1 start")).toHaveValue("07:00");
    expect(screen.getByLabelText("Shift 1 end")).toHaveValue("15:00");
    expect(screen.getByLabelText("Shift 2 start")).toHaveValue("23:00");
    expect(screen.getByLabelText("Shift 2 end")).toHaveValue("06:00");
  });

  it("emits an added empty row on Add shift and caps the editor at three rows", () => {
    const onChange = vi.fn();
    const single = render(<ShiftConfigEditor value={[{ startTime: "07:00", endTime: "15:00" }]} onChange={onChange} />);

    fireEvent.click(screen.getByRole("button", { name: /add shift/i }));
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith([
      { startTime: "07:00", endTime: "15:00" },
      { startTime: "", endTime: "" },
    ]);
    single.unmount();

    render(
      <ShiftConfigEditor
        value={[
          { startTime: "07:00", endTime: "15:00" },
          { startTime: "15:00", endTime: "23:00" },
          { startTime: "23:00", endTime: "06:00" },
        ]}
        onChange={onChange}
      />,
    );
    expect(screen.queryByRole("button", { name: /add shift/i })).toBeNull();
  });

  it("emits the remaining rows after Remove", () => {
    const onChange = vi.fn();
    render(
      <ShiftConfigEditor
        value={[
          { startTime: "07:00", endTime: "15:00" },
          { startTime: "23:00", endTime: "06:00" },
        ]}
        onChange={onChange}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /remove shift 1/i }));
    expect(onChange).toHaveBeenCalledWith([{ startTime: "23:00", endTime: "06:00" }]);
  });

  it("edits a single field without touching sibling rows", () => {
    const onChange = vi.fn();
    render(
      <ShiftConfigEditor
        value={[
          { startTime: "07:00", endTime: "15:00" },
          { startTime: "23:00", endTime: "06:00" },
        ]}
        onChange={onChange}
      />,
    );

    fireEvent.change(screen.getByLabelText("Shift 2 end"), { target: { value: "05:30" } });
    expect(onChange).toHaveBeenCalledWith([
      { startTime: "07:00", endTime: "15:00" },
      { startTime: "23:00", endTime: "05:30" },
    ]);
  });

  it("renders read-only inputs with the LEADER hint when readOnly and no disabledReason", () => {
    render(<ShiftConfigEditor value={[{ startTime: "07:00", endTime: "15:00" }]} onChange={vi.fn()} readOnly />);

    expect(screen.getByLabelText("Shift 1 start")).toHaveAttribute("disabled");
    expect(screen.getByLabelText("Shift 1 end")).toHaveAttribute("disabled");
    expect(screen.queryByRole("button", { name: /add shift/i })).toBeNull();
    expect(screen.queryByRole("button", { name: /remove shift/i })).toBeNull();
    expect(screen.getByText(/requires job scope leader or above/i)).toBeTruthy();
  });

  it("shows the disabledReason instead of the default hint in read-only mode", () => {
    render(<ShiftConfigEditor value={[]} onChange={vi.fn()} readOnly disabledReason="Read-only for your role." />);

    expect(screen.getByText("Read-only for your role.")).toBeTruthy();
    expect(screen.queryByText(/requires job scope leader or above/i)).toBeNull();
  });

  it("exposes validation errors via role=alert and aria-invalid", () => {
    render(
      <ShiftConfigEditor
        value={[{ startTime: "", endTime: "" }]}
        onChange={vi.fn()}
        error="Shift 1 requires both times."
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("Shift 1 requires both times.");
    expect(screen.getByLabelText("Shift 1 start")).toHaveAttribute("aria-invalid", "true");
  });
});
