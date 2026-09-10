import { notFound } from "next/navigation";
import { locale as localeRootParam } from "next/root-params";

import { hasLocale } from "next-intl";
import { getRequestConfig } from "next-intl/server";

import enMessages from "@/messages/en.json";
import idMessages from "@/messages/id.json";

import { routing } from "./routing";

interface Messages {
  [key: string]: string | Messages;
}

// English is the fallback catalog: keys missing from `id` resolve to the English
// string instead of rendering raw keys (Epic 23 constraint; full en-complete
// auditing is 23.4's job).
// ponytail: mergeMessages ships BOTH catalogs to every non-en response — the
// trade-off is accepted at the measured 2,006 leaves per catalog (23.4 audit).
// If /id RSC payloads measurably regress, switch to next-intl getMessageFallback
// or per-locale catalogs without the merge.
// Story 23.4: exported (behavior unchanged) so request.test.ts can pin the
// fallback contract and the completeness tooling can reuse the merge semantics.
export function mergeMessages(fallback: Messages, messages: Messages): Messages {
  const merged: Messages = { ...fallback };
  for (const [key, value] of Object.entries(messages)) {
    const existing = merged[key];
    merged[key] =
      typeof value === "string" || typeof existing !== "object" || Array.isArray(existing)
        ? value
        : mergeMessages(existing, value);
  }
  return merged;
}

// Resolve the locale from Next 16 root params instead of the (version-volatile)
// getRequestConfig callback argument; every route lives under [locale], so the
// segment is always present. Unknown values (e.g. /fr/...) 404 instead of
// silently serving the default.
export default getRequestConfig(async () => {
  const locale = await localeRootParam();
  if (!hasLocale(routing.locales, locale)) {
    notFound();
  }

  const en = enMessages as Messages;
  return {
    locale,
    messages: locale === "en" ? en : mergeMessages(en, idMessages as Messages),
  };
});
