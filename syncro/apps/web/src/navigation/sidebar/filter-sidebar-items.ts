import type { AuthUser } from "@/lib/auth/auth-session";
import type { NavGroup, NavMainItem } from "@/navigation/sidebar/sidebar-items";

export function filterSidebarItems(groups: readonly NavGroup[], role: AuthUser["applicationRole"] | null) {
  return groups
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => canAccessNavItem(item, role)),
    }))
    .filter((group) => group.items.length > 0);
}

export function canAccessNavItem(item: Pick<NavMainItem, "roles">, role: AuthUser["applicationRole"] | null) {
  if (!item.roles?.length) {
    return role !== null;
  }

  return role !== null && item.roles.includes(role);
}
