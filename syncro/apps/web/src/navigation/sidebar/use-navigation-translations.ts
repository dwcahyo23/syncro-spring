import { useTranslations } from "next-intl";

import type { NavGroup } from "@/navigation/sidebar/sidebar-items";

/**
 * Resolve message-keyed sidebar data against the `navigation` catalog.
 *
 * Catalog structure for story 23-1: `common` + `navigation` namespaces are
 * seeded; story 23-2 adds one top-level key per `src/features/<domain>`
 * directory (workorders, alerts, …) without further structural change.
 */
export function useNavigationTranslations() {
  const t = useTranslations("navigation");
  return {
    groupLabel: (group: NavGroup) => (group.labelKey ? t(group.labelKey) : undefined),
    itemTitle: (titleKey: string) => t(titleKey),
  };
}
