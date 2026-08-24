import { fireEvent, render, screen } from "@testing-library/react";
import { beforeAll, describe, expect, it, vi } from "vitest";

import { CurrencyPriceInput } from "./currency-price-input";

beforeAll(() => {
  Object.defineProperty(Element.prototype, "hasPointerCapture", {
    configurable: true,
    value: () => false,
  });
  Object.defineProperty(Element.prototype, "releasePointerCapture", {
    configurable: true,
    value: () => undefined,
  });
  Object.defineProperty(Element.prototype, "scrollIntoView", {
    configurable: true,
    value: () => undefined,
  });
});

describe("CurrencyPriceInput", () => {
  it("defaults to IDR and hides the kurs field", () => {
    const onChange = vi.fn();
    render(<CurrencyPriceInput value={{ amount: "", currency: "IDR", kursToIdr: "" }} onChange={onChange} />);

    expect(screen.getByTestId("price-amount-input")).toHaveValue("");
    expect(screen.getByTestId("price-currency-select")).toHaveTextContent("IDR");
    expect(screen.queryByTestId("price-kurs-input")).not.toBeInTheDocument();
  });

  it("shows the kurs field only for non-IDR currencies", () => {
    const onChange = vi.fn();
    const { rerender } = render(
      <CurrencyPriceInput value={{ amount: "1000", currency: "USD", kursToIdr: "" }} onChange={onChange} />,
    );

    expect(screen.getByTestId("price-kurs-input")).toBeInTheDocument();
    expect(screen.getByText("Required for non-IDR currencies.")).toBeInTheDocument();

    rerender(<CurrencyPriceInput value={{ amount: "1000", currency: "IDR", kursToIdr: "" }} onChange={onChange} />);
    expect(screen.queryByTestId("price-kurs-input")).not.toBeInTheDocument();
  });

  it("emits the updated value object when the amount changes", () => {
    const onChange = vi.fn();
    render(<CurrencyPriceInput value={{ amount: "", currency: "USD", kursToIdr: "15500" }} onChange={onChange} />);

    fireEvent.change(screen.getByTestId("price-amount-input"), { target: { value: "1500000" } });

    expect(onChange).toHaveBeenCalledWith({ amount: "1500000", currency: "USD", kursToIdr: "15500" });
  });

  it("emits the updated value object when the currency changes", async () => {
    const onChange = vi.fn();
    render(<CurrencyPriceInput value={{ amount: "1000", currency: "IDR", kursToIdr: "" }} onChange={onChange} />);

    const trigger = screen.getByTestId("price-currency-select");
    trigger.focus();
    fireEvent.keyDown(trigger, { key: "ArrowDown" });
    fireEvent.click(await screen.findByRole("option", { name: "SGD" }));

    expect(onChange).toHaveBeenCalledWith({ amount: "1000", currency: "SGD", kursToIdr: "" });
  });

  it("shows field errors with alert roles", () => {
    render(
      <CurrencyPriceInput
        value={{ amount: "-5", currency: "usd", kursToIdr: "" }}
        onChange={() => undefined}
        errors={{
          amount: "Amount must be greater than zero.",
          currency: "Currency must be an uppercase ISO-4217 code.",
        }}
      />,
    );

    expect(screen.getAllByRole("alert")[0]).toHaveTextContent("Amount must be greater than zero.");
    expect(screen.getAllByRole("alert")[1]).toHaveTextContent("Currency must be an uppercase ISO-4217 code.");
    expect(screen.getByTestId("price-amount-input")).toBeInvalid();
  });

  it("disables inputs in read-only mode including a shown kurs field", () => {
    render(
      <CurrencyPriceInput
        value={{ amount: "1000", currency: "USD", kursToIdr: "15500" }}
        onChange={() => undefined}
        readOnly
      />,
    );

    expect(screen.getByTestId("price-amount-input")).toBeDisabled();
    expect(screen.getByTestId("price-currency-select")).toBeDisabled();
    expect(screen.getByTestId("price-kurs-input")).toBeDisabled();
  });

  it("shows kurs errors next to the kurs field when provided", () => {
    render(
      <CurrencyPriceInput
        value={{ amount: "1000", currency: "EUR", kursToIdr: "" }}
        onChange={() => undefined}
        errors={{ kursToIdr: "Exchange rate to IDR is required for non-IDR currencies and must be positive." }}
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent(
      "Exchange rate to IDR is required for non-IDR currencies and must be positive.",
    );
    expect(screen.getByTestId("price-kurs-input")).toBeInvalid();
  });
});
