import DOMPurify from "dompurify";

/**
 * HTML sanitizer for stored print-template bodies (spec "Template Laporan Cetak").
 * Templates are saved verbatim and ALWAYS pass through here at render time — a
 * malicious payload that slipped into the DB (script tag, inline event handler,
 * javascript: URL) is stripped before it can reach the DOM.
 *
 * Allowlist = the tags the editor's TipTap extensions produce, plus the table
 * family. `style` stays OUT: all editor formatting (cell/block alignment, text
 * color, highlight, font family) renders as CLASSES — the sanitizer allowlist is
 * the trust boundary, and a fixed class palette is far easier to reason about
 * and to restyle in print.css than filtered CSS text. DOMPurify still enforces
 * URL safety for the newly allowed `a[href]`/`img[src]` (javascript: and
 * data:text/html die here; its default URI checks apply).
 * The list replaces DOMPurify's defaults entirely, so anything outside it
 * (iframe, object, on*) is removed.
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
  "sub",
  "sup",
  "span",
  "a",
  "img",
  "input",
  "label",
  // task-item content wrapper (see TaskItem class override in rich-text-editor)
  "div",
  "table",
  "thead",
  "tbody",
  "tr",
  "th",
  "td",
  "colgroup",
  "col",
];

const ALLOWED_ATTR = [
  "colspan",
  "rowspan",
  "class",
  "href",
  "src",
  "alt",
  "title",
  "target",
  "rel",
  // task-list checkbox (rendered read-only in print; see print.css)
  "type",
  "checked",
];

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
