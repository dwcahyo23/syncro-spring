"use client";

import { Button } from "@/components/ui/button";
import { Link } from "@/i18n/navigation";

// Rendered inside [locale]/layout (notFound() from the layout or the catch-all
// segment), so the NextIntlClientProvider context is available.
export default function NotFound() {
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
