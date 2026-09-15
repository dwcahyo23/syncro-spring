import { describe, expect, it } from "vitest";

import { sanitizeHtml } from "@/lib/html";

/**
 * Frozen-matrix coverage: "Payload jahat tersimpan (bug) → DOMPurify strip saat
 * render" (AC4) plus the table-allowlist rows — a stored TipTap table must
 * survive with its structure, a stored script/onerror must not.
 */
describe("sanitizeHtml", () => {
  it("strips <script> tags", () => {
    const dirty = "<p>halo</p><script>window.location='evil'</script>";
    const clean = sanitizeHtml(dirty);
    expect(clean).not.toContain("<script");
    expect(clean).not.toContain("evil");
    expect(clean).toContain("<p>halo</p>");
  });

  it("strips inline event handlers (onerror, onclick)", () => {
    const dirty = '<img src="x" onerror="steal()"><p onclick="steal()">kronologis {{nomor}}</p>';
    const clean = sanitizeHtml(dirty);
    expect(clean).not.toContain("onerror");
    expect(clean).not.toContain("onclick");
    expect(clean).not.toContain("steal");
    // img is in the allowlist now (URL-based editor insert) — only the handler dies.
    expect(clean).toContain("<img");
    expect(clean).toContain("kronologis {{nomor}}");
  });

  it("strips javascript: urls and iframes", () => {
    const dirty = '<iframe src="https://evil"></iframe><a href="javascript:alert(1)">x</a>';
    const clean = sanitizeHtml(dirty);
    expect(clean).not.toContain("iframe");
    expect(clean).not.toContain("javascript:");
  });

  it("keeps safe links and images with their href/src", () => {
    const clean = sanitizeHtml(
      '<a href="https://syncro.example.com/doc">dok</a><img src="https://cdn.example.com/a.png" alt="foto">',
    );
    expect(clean).toContain('href="https://syncro.example.com/doc"');
    expect(clean).toContain('src="https://cdn.example.com/a.png"');
    expect(clean).toContain("dok");
  });

  it("keeps the editor class palette (align/color/highlight/font/task) and strips data-*", () => {
    const clean = sanitizeHtml(
      '<p class="text-align-center"><span class="tx-color-2563eb ff-arial">a</span>' +
        '<mark class="hl-fde047">b</mark></p>' +
        '<ul class="task-list"><li class="task-item task-checked"><label><input type="checkbox" checked><span></span></label><div>c</div></li></ul>' +
        '<table><tr><td class="cell-align-right" colspan="2" data-x="evil">d</td></tr></table>',
    );
    expect(clean).toContain('class="text-align-center"');
    expect(clean).toContain("tx-color-2563eb");
    expect(clean).toContain("hl-fde047");
    expect(clean).toContain("task-item");
    expect(clean).toContain('type="checkbox"');
    expect(clean).toContain("checked");
    expect(clean).toContain("cell-align-right");
    expect(clean).not.toContain("data-x");
  });

  it("preserves the full table family and span attributes", () => {
    const table =
      '<table><thead><tr><th colspan="2">Kop</th></tr></thead><tbody><tr><td rowspan="2">a</td><td>b</td></tr><tr><td>c</td></tr></tbody></table>';
    expect(sanitizeHtml(table)).toBe(table);
  });

  it("preserves colgroup/col and class", () => {
    const html = '<table><colgroup><col class="w-1/2"><col></colgroup><tr><td class="cell">x</td></tr></table>';
    const clean = sanitizeHtml(html);
    expect(clean).toContain("<colgroup>");
    expect(clean).toContain("<col");
    expect(clean).toContain('class="cell"');
  });

  it("strips style attributes but keeps the element (borders come from CSS)", () => {
    const clean = sanitizeHtml('<p style="color:red">teks</p>');
    expect(clean).not.toContain("style");
    expect(clean).toContain("teks");
  });

  it("keeps the text of stripped elements (KEEP_CONTENT)", () => {
    const clean = sanitizeHtml("<section><span>isi</span><object>buang</object></section>");
    expect(clean).toContain("isi");
    expect(clean).not.toContain("<section");
    expect(clean).not.toContain("<object");
    // KEEP_CONTENT: the removed element's tag dies, its text stays.
    expect(clean).toContain("buang");
  });
});
