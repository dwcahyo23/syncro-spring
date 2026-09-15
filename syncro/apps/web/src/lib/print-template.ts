/**
 * Print-template placeholder substitution (spec "Template Laporan Cetak").
 * `{{key}}` tokens are replaced ONLY inside text nodes of already-sanitized
 * template HTML (DOMPurify runs first — see lib/html.ts). Substituted values
 * are inserted as plain text, so report data can never inject markup.
 *
 * Deliberate limits (spec's design decision "Substitusi hanya di text node"):
 * a token typed inside an attribute, or split across inline marks (e.g. a
 * half-boldened token), stays literal rather than being substituted. Tables
 * and all other structure are preserved untouched.
 */

/** Canonical empty-field marker required by the spec. */
export const EMPTY_PLACEHOLDER_VALUE = "—";

const TOKEN_PATTERN = /\{\{\s*([A-Za-z][A-Za-z0-9_]*)\s*\}\}/g;

export type PlaceholderValues = Record<string, string | null | undefined>;

function displayValue(raw: string | null | undefined): string {
  if (raw == null) return EMPTY_PLACEHOLDER_VALUE;
  const trimmed = raw.trim();
  return trimmed === "" ? EMPTY_PLACEHOLDER_VALUE : raw;
}

/**
 * Resolve every `{{key}}` in text nodes against `values`; null/blank/unknown
 * keys print as "—" so a template never leaks raw tokens onto paper. Returns
 * an empty string when called outside the DOM (the substitution only ever
 * runs in the browser, right before rendering a print page).
 */
export function substitutePlaceholders(html: string, values: PlaceholderValues): string {
  if (typeof document === "undefined") {
    return "";
  }
  const container = document.createElement("div");
  container.innerHTML = html;

  const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT);
  let node: Node | null = walker.nextNode();
  while (node) {
    const text = node.textContent ?? "";
    TOKEN_PATTERN.lastIndex = 0;
    if (TOKEN_PATTERN.test(text)) {
      node.textContent = text.replace(TOKEN_PATTERN, (_match, key: string) => displayValue(values[key]));
    }
    node = walker.nextNode();
  }

  return container.innerHTML;
}

/**
 * A saved layout counts as "cleared" (print falls back to the built-in default)
 * when it has no text at all and no structural element worth printing (table,
 * hr). An all-blank TipTap document ("<p></p>") therefore behaves like no
 * template — which is exactly the "matikan template (clear) → bawaan kembali"
 * flow — while a grid-only table (blank cells for handwriting) stays a real
 * custom layout.
 */
export function isLayoutEmpty(html: string): boolean {
  if (typeof document === "undefined") {
    return true;
  }
  const el = document.createElement("div");
  el.innerHTML = html;
  if (el.querySelector("table, hr")) {
    return false;
  }
  return (el.textContent ?? "").trim() === "";
}

/** Report types matching the backend `report_print_templates.report_type` contract. */
export type ReportPrintTemplateType = "WORK_ORDER" | "PREVENTIVE";

/**
 * Placeholder inventory per report type, derived from the fields the current
 * print components actually load (spec's Always block): the 14 WO keys verbatim
 * from `workorder-print-report.tsx`; the Preventive equivalents mapped field by
 * field to `preventive-report.tsx` (nomor=workOrderId, tanggal=dueDate,
 * kategori=category, status=scheduleStatus, teknisi=performedBy,
 * deskripsi=programTitle, jadwal=scheduleType, penilaian=assessment,
 * catatan=notes, persetujuan=signerIdentity).
 *
 * The narrative/CPK keys (kronologis, analisis, korektif, pencegahan, cpk,
 * fmea, stopTime) and `mesin` have NO Preventive counterpart in the current
 * Preventive print data (it only exposes a raw machine UUID), so they are
 * deliberately absent from the Preventive list per the spec's Ask First rule
 * ("preventive default layout tidak memuat data setara").
 *
 * `as const` + the Record<> types below turn the inventory into a compile-time
 * contract: the print pages annotate their placeholder maps with
 * WorkOrderPlaceholderValues / PreventivePlaceholderValues, so renaming or
 * dropping a key on either side fails `tsc --noEmit`.
 */
export const REPORT_TEMPLATE_PLACEHOLDERS = {
  WORK_ORDER: [
    "nomor",
    "tanggal",
    "mesin",
    "kategori",
    "status",
    "teknisi",
    "deskripsi",
    "kronologis",
    "analisis",
    "korektif",
    "pencegahan",
    "cpk",
    "fmea",
    "stopTime",
  ],
  PREVENTIVE: [
    "nomor",
    "tanggal",
    "kategori",
    "status",
    "teknisi",
    "deskripsi",
    "jadwal",
    "penilaian",
    "catatan",
    "persetujuan",
  ],
} as const;

/** Union of the placeholder token keys for one report type. */
export type ReportPrintTemplateKey<T extends ReportPrintTemplateType> =
  (typeof REPORT_TEMPLATE_PLACEHOLDERS)[T][number];

/** Total placeholder map the WORK_ORDER print page must supply (all 14 keys). */
export type WorkOrderPlaceholderValues = Record<ReportPrintTemplateKey<"WORK_ORDER">, string | null | undefined>;

/** Total placeholder map the PREVENTIVE print page must supply (all 10 keys). */
export type PreventivePlaceholderValues = Record<ReportPrintTemplateKey<"PREVENTIVE">, string | null | undefined>;
