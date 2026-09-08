"use client";

import { EllipsisVertical, LogOut, Settings } from "lucide-react";

import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { SidebarMenu, SidebarMenuButton, SidebarMenuItem, useSidebar } from "@/components/ui/sidebar";
import { useRouter } from "@/i18n/navigation";
import { logoutFromSyncro } from "@/lib/api/syncro-api";
import { clearAuthSession, getAuthToken } from "@/lib/auth/auth-client";
import type { AuthUser } from "@/lib/auth/auth-session";

const roleLabels: Record<AuthUser["applicationRole"], string> = {
  SUPER_ADMIN: "Super Admin",
  MANAGER_MAINTENANCE: "Manager Maintenance",
  MAINTENANCE_LEADER: "Maintenance Leader",
  SECTION_LEADER: "Section Leader",
  STAFF_MAINTENANCE: "Staff Maintenance",
  TECHNICIAN: "Technician",
  INVENTORY_MAINTENANCE: "Inventory Maintenance",
  STOREKEEPER: "Storekeeper",
  PRODUCTION_LEADER: "Production Leader",
  AUDITOR: "Auditor",
};

export function NavUser({ user }: { readonly user: AuthUser | null }) {
  const { isMobile } = useSidebar();
  const router = useRouter();

  if (!user) {
    return null;
  }

  const initials = getUserInitials(user.loginIdentifier);
  const roleLabel = roleLabels[user.applicationRole];

  const handleLogout = async () => {
    const token = getAuthToken();
    await logoutFromSyncro(token);
    clearAuthSession();
    router.replace("/auth/v2/login");
  };

  return (
    <SidebarMenu>
      <SidebarMenuItem>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <SidebarMenuButton
              size="lg"
              className="data-[state=open]:bg-sidebar-accent data-[state=open]:text-sidebar-accent-foreground"
            >
              <Avatar className="h-8 w-8 rounded-lg grayscale">
                <AvatarFallback className="rounded-lg">{initials}</AvatarFallback>
              </Avatar>
              <div className="grid flex-1 text-left text-sm leading-tight">
                <span className="truncate font-medium">{user.loginIdentifier}</span>
                <span className="truncate text-muted-foreground text-xs">{roleLabel}</span>
              </div>
              <EllipsisVertical className="ml-auto size-4" />
            </SidebarMenuButton>
          </DropdownMenuTrigger>
          <DropdownMenuContent
            className="w-(--radix-dropdown-menu-trigger-width) min-w-56 rounded-lg"
            side={isMobile ? "bottom" : "right"}
            align="end"
            sideOffset={4}
          >
            <DropdownMenuLabel className="p-0 font-normal">
              <div className="flex items-center gap-2 px-1 py-1.5 text-left text-sm">
                <Avatar className="h-8 w-8 rounded-lg">
                  <AvatarFallback className="rounded-lg">{initials}</AvatarFallback>
                </Avatar>
                <div className="grid flex-1 text-left text-sm leading-tight">
                  <span className="truncate font-medium">{user.loginIdentifier}</span>
                  <span className="truncate text-muted-foreground text-xs">{roleLabel}</span>
                </div>
              </div>
            </DropdownMenuLabel>
            <DropdownMenuSeparator />
            <DropdownMenuItem onSelect={() => router.push("/settings")}>
              <Settings />
              Settings
            </DropdownMenuItem>
            <DropdownMenuItem onSelect={handleLogout}>
              <LogOut />
              Log out
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </SidebarMenuItem>
    </SidebarMenu>
  );
}

function getUserInitials(loginIdentifier: string) {
  const [name] = loginIdentifier.split("@");
  return (
    name
      .split(/[._-]/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase())
      .join("") || "SY"
  );
}
