import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { SparepartImageUpload } from "./sparepart-image-upload";

describe("SparepartImageUpload", () => {
  it("renders the empty state when no image", () => {
    render(<SparepartImageUpload value={null} onUpload={() => undefined} onRemove={() => undefined} />);

    expect(screen.getByTestId("sparepart-image-empty")).toBeInTheDocument();
    expect(screen.queryByTestId("sparepart-image-preview")).not.toBeInTheDocument();
  });

  it("renders the preview image when a presigned URL is provided", () => {
    render(
      <SparepartImageUpload value="https://presigned/a.png" onUpload={() => undefined} onRemove={() => undefined} />,
    );

    const preview = screen.getByTestId("sparepart-image-preview") as HTMLImageElement;
    expect(preview).toHaveAttribute("src", "https://presigned/a.png");
    expect(screen.getByRole("button", { name: "Replace image" })).toBeInTheDocument();
  });

  it("emits the selected file through onUpload", () => {
    const onUpload = vi.fn();
    render(<SparepartImageUpload value={null} onUpload={onUpload} onRemove={() => undefined} />);

    const file = new File(["image"], "part.png", { type: "image/png" });
    fireEvent.change(screen.getByTestId("sparepart-image-input"), { target: { files: [file] } });

    expect(onUpload).toHaveBeenCalledWith(file);
  });

  it("emits onRemove when the Remove button is clicked", () => {
    const onRemove = vi.fn();
    render(<SparepartImageUpload value="https://presigned/a.png" onUpload={() => undefined} onRemove={onRemove} />);

    fireEvent.click(screen.getByRole("button", { name: "Remove" }));

    expect(onRemove).toHaveBeenCalledTimes(1);
  });

  it("disables actions while uploading", () => {
    render(<SparepartImageUpload value={null} onUpload={() => undefined} onRemove={() => undefined} isUploading />);

    expect(screen.getByRole("button", { name: /upload image/i })).toBeDisabled();
  });

  it("shows error with alert role when provided", () => {
    render(
      <SparepartImageUpload
        value={null}
        onUpload={() => undefined}
        onRemove={() => undefined}
        error="Upload failed."
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("Upload failed.");
  });

  it("hides actions in read-only mode and shows the disabled reason", () => {
    render(
      <SparepartImageUpload
        value="https://presigned/a.png"
        onUpload={() => undefined}
        onRemove={() => undefined}
        readOnly
        disabledReason="View only."
      />,
    );

    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(screen.getByText("View only.")).toBeInTheDocument();
  });
});
