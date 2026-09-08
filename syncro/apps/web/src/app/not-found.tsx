"use client";

import Link from "next/link";

import { Button } from "@/components/ui/button";

// Fallback for 404s thrown ABOVE the [locale] segment (unsupported locale
// prefixes like /fr are rejected in [locale]/layout, so the localized
// [locale]/not-found never renders for them). Unprefixed href is fine — the
// proxy re-prefixes to the default locale.
export default function RootNotFound() {
  return (
    <div className="flex h-dvh flex-col items-center justify-center space-y-2 text-center">
      <h1 className="font-semibold text-2xl">Page not found.</h1>
      <p className="text-muted-foreground">The page you are looking for could not be found.</p>
      <Link prefetch={false} replace href="/operations-overview">
        <Button variant="outline">Go back home</Button>
      </Link>
    </div>
  );
}
