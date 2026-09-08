"use client";

import * as React from "react";

import { Search } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Command,
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandSeparator,
} from "@/components/ui/command";
import { useRouter } from "@/i18n/navigation";
import { useAuthUser } from "@/lib/auth/use-auth-user";
import { filterSidebarItems } from "@/navigation/sidebar/filter-sidebar-items";
import type { NavMainItem } from "@/navigation/sidebar/sidebar-items";
import { sidebarItems } from "@/navigation/sidebar/sidebar-items";
import { useNavigationTranslations } from "@/navigation/sidebar/use-navigation-translations";

type SearchItem = {
  group: string;
  label: string;
  url: string;
  icon?: NavMainItem["icon"];
  disabled?: boolean;
  newTab?: boolean;
};

function buildSearchItems(
  groups: ReturnType<typeof filterSidebarItems>,
  nt: ReturnType<typeof useNavigationTranslations>,
): SearchItem[] {
  return groups.flatMap((group) => {
    const groupLabel = nt.groupLabel(group);
    return group.items.flatMap((item) => {
      const itemTitle = nt.itemTitle(item.titleKey);
      if (item.subItems) {
        return item.subItems.map((sub) => ({
          // Sub-items are grouped under the parent item label unless the parent
          // duplicates the group label (then keep the group label).
          group: groupLabel && groupLabel !== itemTitle ? itemTitle : (groupLabel ?? itemTitle),
          label: nt.itemTitle(sub.titleKey),
          url: sub.url,
          icon: item.icon,
          disabled: sub.comingSoon,
          newTab: sub.newTab,
        }));
      }
      return [
        {
          group: groupLabel ?? itemTitle,
          label: itemTitle,
          url: item.url,
          icon: item.icon,
          disabled: item.comingSoon,
          newTab: item.newTab,
        },
      ];
    });
  });
}

function getAvailableItems(items: SearchItem[]) {
  return items.filter((item) => !item.disabled && !item.url.includes("coming-soon"));
}

function groupBy(items: SearchItem[]) {
  const groups = [...new Set(items.map((item) => item.group))];
  return groups.map((group) => ({
    group,
    items: items.filter((item) => item.group === group),
  }));
}

export function SearchDialog() {
  const [open, setOpen] = React.useState(false);
  const [query, setQuery] = React.useState("");
  const router = useRouter();
  const user = useAuthUser();
  const nt = useNavigationTranslations();
  const searchItems = buildSearchItems(filterSidebarItems(sidebarItems, user?.applicationRole ?? null), nt);
  const recommendations = getAvailableItems(searchItems);

  React.useEffect(() => {
    const down = (e: KeyboardEvent) => {
      if (e.key === "j" && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        setOpen((prev) => !prev);
      }
    };
    document.addEventListener("keydown", down);
    return () => document.removeEventListener("keydown", down);
  }, []);

  const handleOpenChange = (value: boolean) => {
    setOpen(value);
    if (!value) setQuery("");
  };

  const handleSelect = (item: SearchItem) => {
    if (item.disabled) return;
    handleOpenChange(false);
    if (item.newTab) {
      window.open(item.url, "_blank", "noopener,noreferrer");
    } else {
      router.push(item.url);
    }
  };

  const renderGroups = (items: SearchItem[]) =>
    groupBy(items).map(({ group, items: groupItems }, index) => (
      <React.Fragment key={group}>
        {index > 0 && <CommandSeparator />}
        <CommandGroup heading={group}>
          {groupItems.map((item) => (
            <CommandItem
              disabled={item.disabled}
              key={`${group}-${item.url}-${item.label}`}
              value={`${item.group} ${item.label}`}
              onSelect={() => handleSelect(item)}
            >
              {item.icon && <item.icon />}
              <span>{item.label}</span>

              {item.disabled && (
                <Badge variant="outline" className="text-xs">
                  Soon
                </Badge>
              )}
            </CommandItem>
          ))}
        </CommandGroup>
      </React.Fragment>
    ));

  return (
    <>
      <Button
        onClick={() => handleOpenChange(true)}
        variant="link"
        className="px-0! font-normal text-muted-foreground hover:no-underline"
      >
        <Search data-icon="inline-start" />
        Search
        <kbd className="inline-flex h-5 select-none items-center gap-1 rounded border bg-muted px-1.5 font-medium text-[0.65rem]">
          <span className="text-xs">⌘</span>J
        </kbd>
      </Button>
      <CommandDialog open={open} onOpenChange={handleOpenChange}>
        <Command>
          <CommandInput placeholder="Search dashboards, users, and more…" value={query} onValueChange={setQuery} />
          <CommandList>
            <CommandEmpty>No results found.</CommandEmpty>
            {query ? renderGroups(searchItems) : renderGroups(recommendations)}
          </CommandList>
        </Command>
      </CommandDialog>
    </>
  );
}
