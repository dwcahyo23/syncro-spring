import Link from "next/link";

import { ExternalLink } from "lucide-react";

type HealthEvidenceLinkProps = {
  readonly href: string;
  readonly children: string;
};

/**
 * Deep link from a health failure card to its operational evidence (e.g. a failing alert's
 * notification history). Rendered next to — never inside — {@link HealthCard}, which stays
 * read-only by design. Navigation only; no mutation controls.
 */
export function HealthEvidenceLink({ href, children }: HealthEvidenceLinkProps) {
  return (
    <Link
      href={href}
      className="inline-flex items-center gap-1 font-medium text-primary text-xs underline-offset-4 hover:underline"
    >
      {children}
      <ExternalLink aria-hidden="true" className="size-3 shrink-0" />
    </Link>
  );
}
