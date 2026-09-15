"use client";

import { sanitizeHtml } from "@/lib/html";
import { type PlaceholderValues, substitutePlaceholders } from "@/lib/print-template";

/**
 * Renders a stored print-layout template as report content
 * (spec "Template Laporan Cetak"). Pipeline order is deliberate:
 * DOMPurify sanitize FIRST (a payload that slipped into the DB is stripped
 * before anything touches the DOM), then `{{key}}` substitution into the
 * resulting text nodes. Substituted values are written as plain text, so
 * report data can never inject markup either.
 *
 * Table borders come from the stylesheet (`:where(table)` rules in
 * styles/print.css) because the sanitizer strips `style` attributes —
 * leaders apply structure (tables, headings) instead of inline styles.
 */
export function PrintTemplateView({ html, values }: { html: string; values: PlaceholderValues }) {
  const rendered = substitutePlaceholders(sanitizeHtml(html), values);
  // biome-ignore lint/security/noDangerouslySetInnerHtml: html is DOMPurify-sanitized before substitution; substituted values are inserted as text nodes
  return <div className="print-template-body" dangerouslySetInnerHTML={{ __html: rendered }} />;
}
