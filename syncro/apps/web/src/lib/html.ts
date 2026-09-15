import DOMPurify from "dompurify";

/**
 * HTML sanitizer for stored print-template bodies (spec "Template Laporan Cetak").
 * Templates are saved verbatim and ALWAYS pass through here at render time — a
 * malicious payload that slipped into the DB (script tag, inline event handler,
 * javascript: URL) is stripped before it can reach the DOM.
 *
 * Allowlist = the tags TipTap's StarterKit + table extensions produce, plus the
 * table family and `colspan`/`rowspan`/`class`. No `style` (table borders come
 * from CSS), no event attributes. The list replaces DOMPurify's defaults
 * entirely, so anything outside it (img, a, iframe, on*) is removed.
 */
const ALLOWED_TAGS = [
  "p",
  "h1",
  "h2",
  "h3",
  "h4",
  "h5",
  "h6",
  "blockquote",
  "pre",
  "code",
  "hr",
  "br",
  "ul",
  "ol",
  "li",
  "strong",
  "em",
  "s",
  "del",
  "ins",
  "u",
  "mark",
  "span",
  "table",
  "thead",
  "tbody",
  "tr",
  "th",
  "td",
  "colgroup",
  "col",
];

const ALLOWED_ATTR = ["colspan", "rowspan", "class"];

export function sanitizeHtml(dirty: string): string {
  // SSR guard: print pages only render templates client-side (the fetch resolves
  // after mount), so an empty string here is never shown; it exists so importing
  // this module on the server cannot throw.
  if (typeof window === "undefined") {
    return "";
  }
  return DOMPurify.sanitize(dirty, {
    ALLOWED_TAGS,
    ALLOWED_ATTR,
    ALLOW_DATA_ATTR: false,
    ALLOW_ARIA_ATTR: false,
    KEEP_CONTENT: true,
  });
}
