"use client";

import { useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";

export interface InheritedConfigBadgeProps {
  source?: string;
}

/** States group inheritance for machine-level config resolved by the backend. */
export function InheritedConfigBadge({ source }: InheritedConfigBadgeProps) {
  const t = useTranslations("common");
  if (source !== "MACHINE_GROUP") {
    return null;
  }
  return <Badge variant="secondary">{t("inheritedFromGroup")}</Badge>;
}
