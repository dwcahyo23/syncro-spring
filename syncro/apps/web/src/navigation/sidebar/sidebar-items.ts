import {
  Activity,
  Bell,
  CalendarCheck,
  Database,
  FileText,
  Gauge,
  HeartPulse,
  LayoutDashboard,
  type LucideIcon,
  MessageSquare,
  Package,
  Radio,
  Settings,
  Wrench,
} from "lucide-react";

export interface NavSubItem {
  /** Message key in the `navigation` catalog (resolved via `t()` in the render components). */
  titleKey: string;
  url: string;
  icon?: LucideIcon;
  comingSoon?: boolean;
  newTab?: boolean;
  isNew?: boolean;
  roles?: Array<
    | "SUPER_ADMIN"
    | "MANAGER_MAINTENANCE"
    | "MAINTENANCE_LEADER"
    | "SECTION_LEADER"
    | "STAFF_MAINTENANCE"
    | "TECHNICIAN"
    | "INVENTORY_MAINTENANCE"
    | "STOREKEEPER"
    | "PRODUCTION_LEADER"
    | "AUDITOR"
  >;
}

export interface NavMainItem {
  /** Message key in the `navigation` catalog (resolved via `t()` in the render components). */
  titleKey: string;
  url: string;
  icon?: LucideIcon;
  subItems?: NavSubItem[];
  comingSoon?: boolean;
  newTab?: boolean;
  isNew?: boolean;
  roles?: Array<
    | "SUPER_ADMIN"
    | "MANAGER_MAINTENANCE"
    | "MAINTENANCE_LEADER"
    | "SECTION_LEADER"
    | "STAFF_MAINTENANCE"
    | "TECHNICIAN"
    | "INVENTORY_MAINTENANCE"
    | "STOREKEEPER"
    | "PRODUCTION_LEADER"
    | "AUDITOR"
  >;
}

export interface NavGroup {
  id: number;
  /** Message key in the `navigation.groups` catalog (resolved via `t()`). */
  labelKey?: string;
  items: NavMainItem[];
}

export const sidebarItems: NavGroup[] = [
  {
    id: 1,
    labelKey: "groups.operational",
    items: [
      {
        titleKey: "items.operationsOverview",
        url: "/operations-overview",
        icon: Activity,
      },
      {
        titleKey: "items.telemetry",
        url: "/telemetry",
        icon: Radio,
      },
      {
        titleKey: "items.alerts",
        url: "/alerts",
        icon: Bell,
      },
    ],
  },
  {
    id: 2,
    labelKey: "groups.dashboards",
    items: [
      {
        titleKey: "items.machineDashboard",
        url: "/machine-dashboard",
        icon: Gauge,
        roles: [
          "SUPER_ADMIN",
          "MANAGER_MAINTENANCE",
          "MAINTENANCE_LEADER",
          "SECTION_LEADER",
          "PRODUCTION_LEADER",
          "AUDITOR",
        ],
      },
      {
        titleKey: "items.workorderDashboard",
        url: "/workorder-dashboard",
        icon: LayoutDashboard,
        roles: [
          "SUPER_ADMIN",
          "MANAGER_MAINTENANCE",
          "MAINTENANCE_LEADER",
          "SECTION_LEADER",
          "PRODUCTION_LEADER",
          "AUDITOR",
        ],
      },
      {
        titleKey: "items.preventiveDashboard",
        url: "/preventive-dashboard",
        icon: CalendarCheck,
        roles: [
          "SUPER_ADMIN",
          "MANAGER_MAINTENANCE",
          "MAINTENANCE_LEADER",
          "SECTION_LEADER",
          "PRODUCTION_LEADER",
          "AUDITOR",
        ],
      },
      {
        titleKey: "items.analytics",
        url: "/analytics",
        icon: Activity,
        isNew: true,
        roles: [
          "SUPER_ADMIN",
          "MANAGER_MAINTENANCE",
          "MAINTENANCE_LEADER",
          "SECTION_LEADER",
          "PRODUCTION_LEADER",
          "AUDITOR",
        ],
      },
    ],
  },
  {
    id: 3,
    labelKey: "groups.maintenance",
    items: [
      {
        titleKey: "items.workOrders",
        url: "/dashboard/workorders",
        icon: Wrench,
      },
      {
        titleKey: "items.preventive",
        url: "/dashboard/preventive",
        icon: CalendarCheck,
      },
      {
        titleKey: "items.sparepartRequests",
        url: "/dashboard/sparepart-requests",
        icon: Package,
      },
      {
        titleKey: "items.stock",
        url: "/dashboard/stock",
        icon: Package,
      },
    ],
  },
  {
    id: 4,
    labelKey: "groups.configuration",
    items: [
      {
        titleKey: "items.masterData",
        url: "/master-data",
        icon: Database,
        roles: ["SUPER_ADMIN", "MANAGER_MAINTENANCE"],
        subItems: [
          { titleKey: "items.machines", url: "/master-data/machines" },
          { titleKey: "items.organization", url: "/master-data/organization" },
          { titleKey: "items.spareparts", url: "/master-data/spareparts" },
        ],
      },
      {
        titleKey: "items.wahaTemplates",
        url: "/waha-templates",
        icon: MessageSquare,
        roles: ["SUPER_ADMIN"],
      },
      {
        titleKey: "items.auditLog",
        url: "/audit-log",
        icon: FileText,
        roles: ["SUPER_ADMIN", "MANAGER_MAINTENANCE"],
      },
      {
        titleKey: "items.systemHealth",
        url: "/system-health",
        icon: HeartPulse,
        roles: ["SUPER_ADMIN"],
      },
      {
        titleKey: "items.settings",
        url: "/settings",
        icon: Settings,
      },
    ],
  },
];
