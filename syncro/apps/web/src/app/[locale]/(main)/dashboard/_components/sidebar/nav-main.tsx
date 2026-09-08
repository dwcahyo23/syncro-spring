"use client";

import { ChevronRight } from "lucide-react";
import { useTranslations } from "next-intl";

import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarMenuSub,
  SidebarMenuSubButton,
  SidebarMenuSubItem,
  useSidebar,
} from "@/components/ui/sidebar";
import { Link, usePathname } from "@/i18n/navigation";
import { useAuthUser } from "@/lib/auth/use-auth-user";
import { filterSidebarItems } from "@/navigation/sidebar/filter-sidebar-items";
import type { NavGroup, NavMainItem } from "@/navigation/sidebar/sidebar-items";
import { useNavigationTranslations } from "@/navigation/sidebar/use-navigation-translations";

interface NavMainProps {
  readonly items: readonly NavGroup[];
}

type NavigationTranslations = ReturnType<typeof useNavigationTranslations>;

const IsComingSoon = () => {
  const t = useTranslations("common");
  return <span className="ml-auto rounded-md bg-gray-200 px-2 py-1 text-xs dark:text-gray-800">{t("comingSoon")}</span>;
};

const NavItemExpanded = ({
  item,
  title,
  nt,
  isActive,
  isSubmenuOpen,
}: {
  item: NavMainItem;
  title: string;
  nt: NavigationTranslations;
  isActive: (url: string, subItems?: NavMainItem["subItems"]) => boolean;
  isSubmenuOpen: (subItems?: NavMainItem["subItems"]) => boolean;
}) => {
  return (
    <Collapsible key={item.titleKey} asChild defaultOpen={isSubmenuOpen(item.subItems)} className="group/collapsible">
      <SidebarMenuItem>
        <CollapsibleTrigger asChild>
          {item.subItems ? (
            <SidebarMenuButton disabled={item.comingSoon} isActive={isActive(item.url, item.subItems)} tooltip={title}>
              {item.icon && <item.icon />}
              <span>{title}</span>
              {item.comingSoon && <IsComingSoon />}
              <ChevronRight className="ml-auto transition-transform duration-200 group-data-[state=open]/collapsible:rotate-90" />
            </SidebarMenuButton>
          ) : (
            <SidebarMenuButton asChild aria-disabled={item.comingSoon} isActive={isActive(item.url)} tooltip={title}>
              <Link prefetch={false} href={item.url} target={item.newTab ? "_blank" : undefined}>
                {item.icon && <item.icon />}
                <span>{title}</span>
                {item.comingSoon && <IsComingSoon />}
              </Link>
            </SidebarMenuButton>
          )}
        </CollapsibleTrigger>
        {item.subItems && (
          <CollapsibleContent>
            <SidebarMenuSub>
              {item.subItems.map((subItem) => (
                <SidebarMenuSubItem key={subItem.titleKey}>
                  <SidebarMenuSubButton aria-disabled={subItem.comingSoon} isActive={isActive(subItem.url)} asChild>
                    <Link prefetch={false} href={subItem.url} target={subItem.newTab ? "_blank" : undefined}>
                      {subItem.icon && <subItem.icon />}
                      <span>{nt.itemTitle(subItem.titleKey)}</span>
                      {subItem.comingSoon && <IsComingSoon />}
                    </Link>
                  </SidebarMenuSubButton>
                </SidebarMenuSubItem>
              ))}
            </SidebarMenuSub>
          </CollapsibleContent>
        )}
      </SidebarMenuItem>
    </Collapsible>
  );
};

const NavItemCollapsed = ({
  item,
  title,
  nt,
  isActive,
}: {
  item: NavMainItem;
  title: string;
  nt: NavigationTranslations;
  isActive: (url: string, subItems?: NavMainItem["subItems"]) => boolean;
}) => {
  return (
    <SidebarMenuItem key={item.titleKey}>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <SidebarMenuButton disabled={item.comingSoon} tooltip={title} isActive={isActive(item.url, item.subItems)}>
            {item.icon && <item.icon />}
            <span>{title}</span>
            <ChevronRight />
          </SidebarMenuButton>
        </DropdownMenuTrigger>
        <DropdownMenuContent className="w-50 space-y-1" side="right" align="start">
          {item.subItems?.map((subItem) => (
            <DropdownMenuItem key={subItem.titleKey} asChild>
              <SidebarMenuSubButton
                key={subItem.titleKey}
                asChild
                className="focus-visible:ring-0"
                aria-disabled={subItem.comingSoon}
                isActive={isActive(subItem.url)}
              >
                <Link prefetch={false} href={subItem.url} target={subItem.newTab ? "_blank" : undefined}>
                  {subItem.icon && <subItem.icon className="[&>svg]:text-sidebar-foreground" />}
                  <span>{nt.itemTitle(subItem.titleKey)}</span>
                  {subItem.comingSoon && <IsComingSoon />}
                </Link>
              </SidebarMenuSubButton>
            </DropdownMenuItem>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>
    </SidebarMenuItem>
  );
};

export function NavMain({ items }: NavMainProps) {
  const path = usePathname();
  const { state, isMobile } = useSidebar();
  const user = useAuthUser();
  const nt = useNavigationTranslations();
  const filteredItems = filterSidebarItems(items, user?.applicationRole ?? null);

  const isItemActive = (url: string, subItems?: NavMainItem["subItems"]) => {
    // next-intl's usePathname is locale-stripped, so item urls match unchanged.
    if (subItems?.length) {
      return subItems.some((sub) => path.startsWith(sub.url));
    }
    return path === url;
  };

  const isSubmenuOpen = (subItems?: NavMainItem["subItems"]) => {
    return subItems?.some((sub) => path.startsWith(sub.url)) ?? false;
  };

  return (
    <>
      {filteredItems.map((group) => (
        <SidebarGroup key={group.id}>
          {group.labelKey && <SidebarGroupLabel>{nt.groupLabel(group)}</SidebarGroupLabel>}
          <SidebarGroupContent className="flex flex-col gap-2">
            <SidebarMenu>
              {group.items.map((item) => {
                const title = nt.itemTitle(item.titleKey);
                if (state === "collapsed" && !isMobile) {
                  // If no subItems, just render the button as a link
                  if (!item.subItems) {
                    return (
                      <SidebarMenuItem key={item.titleKey}>
                        <SidebarMenuButton
                          asChild
                          aria-disabled={item.comingSoon}
                          tooltip={title}
                          isActive={isItemActive(item.url)}
                        >
                          <Link prefetch={false} href={item.url} target={item.newTab ? "_blank" : undefined}>
                            {item.icon && <item.icon />}
                            <span>{title}</span>
                          </Link>
                        </SidebarMenuButton>
                      </SidebarMenuItem>
                    );
                  }
                  // Otherwise, render the dropdown as before
                  return (
                    <NavItemCollapsed key={item.titleKey} item={item} title={title} nt={nt} isActive={isItemActive} />
                  );
                }
                // Expanded view
                return (
                  <NavItemExpanded
                    key={item.titleKey}
                    item={item}
                    title={title}
                    nt={nt}
                    isActive={isItemActive}
                    isSubmenuOpen={isSubmenuOpen}
                  />
                );
              })}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
      ))}
    </>
  );
}
