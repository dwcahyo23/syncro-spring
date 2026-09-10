import { createElement, type ReactElement, type ReactNode } from "react";

import { render, screen } from "@testing-library/react";
import { NextIntlClientProvider, useTranslations } from "next-intl";
import { describe, expect, it, vi } from "vitest";

// request.ts imports `locale` from next/root-params at module top level — the
// bare runtime file is a compiler placeholder that throws outside a Next build,
// so stub it. getRequestConfig is mocked to its callback: under jsdom the
// package's non-RSC build makes the real one a throwing placeholder, and the
// identity mock lets the wiring test invoke request.ts's callback directly.
vi.mock("next/root-params", () => ({ locale: async () => "id" }));
vi.mock("next-intl/server", () => ({ getRequestConfig: (callback: unknown) => callback }));

import getRequestConfigCallback, { mergeMessages } from "@/i18n/request";
import enMessages from "@/messages/en.json";
import idMessages from "@/messages/id.json";

// Story 23.4 AC3 pin: the English-fallback merge semantics and the resulting
// render behavior (missing id key → English string, never a raw key).

type Messages = { [key: string]: string | Messages };

describe("mergeMessages (English fallback)", () => {
  it("deep id values win over en, en fills id-missing branches", () => {
    const en: Messages = { section: { inBoth: "en shared", onlyEn: "en only" }, top: "en top" };
    const id: Messages = { section: { inBoth: "id shared" }, top: "id top" };
    expect(mergeMessages(en, id)).toEqual({
      section: { inBoth: "id shared", onlyEn: "en only" },
      top: "id top",
    });
  });

  it("primitives replace objects in either direction", () => {
    expect(mergeMessages({ a: { b: "en" } }, { a: "id" })).toEqual({ a: "id" });
    expect(mergeMessages({ a: "en" }, { a: { b: "id" } })).toEqual({ a: { b: "id" } });
  });

  it("arrays pass through (the messages side replaces the fallback side)", () => {
    const en = { list: ["en-a", "en-b"] } as unknown as Messages;
    const id = { list: ["id-a"] } as unknown as Messages;
    expect(mergeMessages(en, id)).toEqual({ list: ["id-a"] });
    // en side missing entirely → id array arrives untouched
    expect(mergeMessages({}, id)).toEqual({ list: ["id-a"] });
  });

  it("on the real catalogs the merge changes nothing (key sets are symmetric)", () => {
    const merged = mergeMessages(enMessages as Messages, idMessages as Messages);
    const leafKeys = (catalog: Messages, prefix = ""): string[] =>
      Object.entries(catalog).flatMap(([key, value]) => {
        const full = prefix ? `${prefix}.${key}` : key;
        return typeof value === "string" ? [full] : leafKeys(value, full);
      });
    expect(leafKeys(merged).sort()).toEqual(leafKeys(enMessages as Messages).sort());
  });
});

describe("request config wiring (AC3 seam)", () => {
  // The top-level getRequestConfig identity mock exposes request.ts's callback
  // as the module default, so it can be invoked directly; the locale stubs to
  // "id". This pins that the served config IS the mergeMessages(en, id) result
  // — if the wiring ever stopped merging (e.g. served idMessages alone), the
  // equality below fails even though mergeMessages itself still works.
  it("for locale id, the served messages are mergeMessages(en, id) — en fills id gaps", async () => {
    const config = await (getRequestConfigCallback as () => Promise<{ locale: string; messages: Messages }>)();
    expect(config.locale).toBe("id");
    // The served config must be exactly the en-fallback merge of both real
    // catalogs — dropping mergeMessages from the wiring (e.g. serving id-only)
    // would break this equality. The AC3 missing-leaf render behavior itself is
    // pinned by the merged-catalog render test below.
    expect(config.messages).toEqual(mergeMessages(enMessages as Messages, idMessages as Messages));
    // Sanity: both catalogs contributed (an en-only structural node is present).
    expect(typeof (config.messages.common as Messages).cancel).toBe("string");
  });
});

describe("merged-catalog render (AC3: fallback string, never a raw key)", () => {
  // createElement, not JSX: the spec names this file .ts (esbuild parses .ts
  // without JSX). The provider + children are the same tree either way.
  const Probe = () => {
    const t = useTranslations("common");
    return createElement(
      "div",
      null,
      createElement("p", { "data-testid": "removed" }, t("cancel")),
      createElement("p", { "data-testid": "present" }, t("save")),
    );
  };

  it("renders the English string for a leaf missing from id, Indonesian when present", () => {
    const en = enMessages as Messages;
    const enCommon = en.common as Messages;
    const id = structuredClone(idMessages) as Messages;
    const idCommon = id.common as Messages;
    expect(typeof idCommon.cancel).toBe("string");
    expect(idCommon.cancel).not.toBe(enCommon.cancel); // fallback assertion must not be vacuous
    const cancelFallback = enCommon.cancel; // capture BEFORE deleting the id leaf
    delete idCommon.cancel; // simulate an id leaf that was never translated
    const messages = mergeMessages(en, id);

    // next-intl types `children` as required in the props object, which fights
    // createElement's variadic-children overload and Biome's noChildrenProp;
    // this local adapter keeps the call honest for TS.
    const Provider = NextIntlClientProvider as unknown as (
      props: { locale: string; messages: Messages },
      ...children: ReactNode[]
    ) => ReactElement;
    render(createElement(Provider, { locale: "id", messages }, createElement(Probe)));

    // English fallback from the merged catalog, side by side with a real id
    // value — both asserted against the imported catalogs, not hardcoded copy,
    // so translator edits to these strings don't break the gate.
    expect(screen.getByTestId("removed").textContent).toBe(cancelFallback);
    expect(screen.getByTestId("present").textContent).toBe(idCommon.save);
    // No raw next-intl key echo anywhere in the DOM.
    expect(document.body.textContent).not.toContain("common.cancel");
    expect(document.body.textContent).not.toContain("MISSING_MESSAGE");
  });
});
