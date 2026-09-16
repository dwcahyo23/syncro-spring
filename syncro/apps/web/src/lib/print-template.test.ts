import { describe, expect, it } from "vitest";

import { isLayoutEmpty, substitutePlaceholders } from "@/lib/print-template";

describe("substitutePlaceholders", () => {
  it("replaces every {{key}} in text nodes with data (matrix: Cetak WO, template ada)", () => {
    const html = "<p>Laporan {{nomor}} — mesin {{mesin}}</p>";
    const out = substitutePlaceholders(html, { nomor: "WO-2609-00001", mesin: "BF-08410 · JBF19" });
    expect(out).toBe("<p>Laporan WO-2609-00001 — mesin BF-08410 · JBF19</p>");
  });

  it("renders '—' for null/blank/unknown keys (matrix: Placeholder data kosong)", () => {
    const out = substitutePlaceholders("<p>{{cpk}} {{fmea}} {{nomor}}</p>", { cpk: null, nomor: "  " });
    expect(out).toBe("<p>— — —</p>");
  });

  it("keeps table structure intact while substituting cells (Ask First: tables must survive)", () => {
    const html = "<table><tbody><tr><td>{{kronologis}}</td><td>fix</td></tr></tbody></table>";
    const out = substitutePlaceholders(html, { kronologis: "Ganti bearing" });
    expect(out).toBe("<table><tbody><tr><td>Ganti bearing</td><td>fix</td></tr></tbody></table>");
  });

  it("does not substitute inside attributes (text-node-only policy)", () => {
    const html = '<p class="{{nomor}}">teks</p>';
    const out = substitutePlaceholders(html, { nomor: "WO-1" });
    expect(out).toBe('<p class="{{nomor}}">teks</p>');
  });

  it("escapes substituted values as text — never as markup", () => {
    const out = substitutePlaceholders("<p>{{deskripsi}}</p>", { deskripsi: "<script>steal()</script>" });
    expect(out).not.toContain("<script>");
    // jsdom re-serializes the escaped text node back to <script> on read, so
    // assert via DOM: the paragraph has exactly one text child, no element child.
    const el = document.createElement("div");
    el.innerHTML = out;
    expect(el.querySelector("script")).toBeNull();
    expect(el.querySelector("p")?.textContent).toBe("<script>steal()</script>");
  });

  it("handles multiple tokens in one text node", () => {
    const out = substitutePlaceholders("<p>{{status}}/{{teknisi}}</p>", { status: "CLOSED", teknisi: "Andi" });
    expect(out).toBe("<p>CLOSED/Andi</p>");
  });

  it("renders image tokens as <img> with the print class", () => {
    const out = substitutePlaceholders("<p>{{logo}}</p>", {
      logo: { imageUrl: "https://garage/logo.png", alt: "Logo", className: "print-logo" },
    });
    expect(out).toContain('<img src="https://garage/logo.png" alt="Logo" class="print-logo">');
  });

  it("renders unavailable image tokens as nothing, never '—' or a broken img", () => {
    const out = substitutePlaceholders("<p>TTD: {{tandaTangan}}.</p>", {
      tandaTangan: { imageUrl: null, alt: "Signature", className: "print-signature" },
    });
    expect(out).toBe("<p>TTD: .</p>");
    expect(out).not.toContain("<img");
    expect(out).not.toContain("—");
  });

  it("keeps surrounding text and mixed tokens intact", () => {
    const out = substitutePlaceholders("<p>WO {{nomor}} <strong>{{logo}}</strong> {{status}}</p>", {
      nomor: "WO-1",
      status: "CLOSED",
      logo: { imageUrl: "https://x/l.png", alt: "L", className: "print-logo" },
    });
    expect(out).toContain("WO WO-1");
    expect(out).toContain("<strong>");
    expect(out).toContain('class="print-logo"');
    expect(out).toContain("CLOSED");
  });
});

describe("isLayoutEmpty", () => {
  it("treats the blank TipTap document as empty", () => {
    expect(isLayoutEmpty("<p></p>")).toBe(true);
    expect(isLayoutEmpty('<p><br class="ProseMirror-trailingBreak"></p>')).toBe(true);
    expect(isLayoutEmpty("")).toBe(true);
  });

  it("treats text as non-empty", () => {
    expect(isLayoutEmpty("<p>Laporan</p>")).toBe(false);
  });

  it("treats a blank-cell grid (handwritten form) as a real layout", () => {
    expect(isLayoutEmpty("<table><tr><td></td><td></td></tr></table>")).toBe(false);
  });

  it("treats a lone horizontal rule as a real layout", () => {
    expect(isLayoutEmpty("<hr>")).toBe(false);
  });
});
