"use client";

import { useTranslations } from "next-intl";

import { Button } from "@/components/ui/button";
import { Link } from "@/i18n/navigation";

// Rendered inside [locale]/layout (notFound() from the layout or the catch-all
// segment). Story 23-2 P4: the layout middleware normalizes unknown prefixes
// (e.g. /fr) onto the default locale before this boundary renders, so the
// provider is always available and the copy resolves from the active locale.
// The root src/app/not-found.tsx (no [locale] segment above it) stays
// static English by design.
export default function NotFound() {
  const t = useTranslations("common");
  return (
    <div className="flex h-dvh flex-col items-center justify-center space-y-2 text-center">
      <h1 className="font-semibold text-2xl">{t("notFound.title")}</h1>
      <p className="text-muted-foreground">{t("notFound.description")}</p>
      <Link prefetch={false} replace href="/operations-overview">
        <Button variant="outline">{t("notFound.goBackHome")}</Button>
      </Link>
    </div>
  );
}
