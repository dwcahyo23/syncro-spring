import { notFound } from "next/navigation";

// Catch-all inside [locale]: unknown paths under any locale (including the
// `/id/...` form next-intl produces for unsupported prefixes like `/fr`)
// render the localized 404 instead of silently matching elsewhere.
export default function CatchAllPage() {
  notFound();
}
