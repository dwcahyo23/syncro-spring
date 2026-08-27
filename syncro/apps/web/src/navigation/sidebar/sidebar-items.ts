import {
  Activity,
  Bell,
  CalendarCheck,
  Database,
  FileText,
  HeartPulse,
  type LucideIcon,
  MessageSquare,
  Package,
  Radio,
  Settings,
  Wrench,
} from "lucide-react";

export interface NavSubItem {
  title: string;
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
  title: string;
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
  label?: string;
  items: NavMainItem[];
}

export const sidebarItems: NavGroup[] = [
  {
    id: 1,
    label: "Operational",
    items: [
      {
        title: "Operations Overview",
        url: "/operations-overview",
        icon: Activity,
      },
      {
        title: "Telemetry",
        url: "/telemetry",
        icon: Radio,
      },
      {
        title: "Alerts",
        url: "/alerts",
        icon: Bell,
      },
    ],
  },
  {
    id: 2,
    label: "Maintenance",
    items: [
      {
        title: "Work Orders",
        url: "/dashboard/workorders",
        icon: Wrench,
      },
      {
        title: "Preventive",
        url: "/dashboard/preventive",
        icon: CalendarCheck,
      },
      {
        title: "Sparepart Requests",
        url: "/dashboard/sparepart-requests",
        icon: Package,
      },
    ],
  },
  {
    id: 3,
    label: "Configuration",
    items: [
      {
        title: "Master Data",
        url: "/master-data",
        icon: Database,
        roles: ["SUPER_ADMIN", "MANAGER_MAINTENANCE"],
        subItems: [
          { title: "Machines", url: "/master-data/machines" },
          { title: "Organization", url: "/master-data/organization" },
          { title: "Spareparts", url: "/master-data/spareparts" },
        ],
      },
      {
        title: "WAHA Templates",
        url: "/waha-templates",
        icon: MessageSquare,
        roles: ["SUPER_ADMIN"],
      },
      {
        title: "Audit Log",
        url: "/audit-log",
        icon: FileText,
        roles: ["SUPER_ADMIN", "MANAGER_MAINTENANCE"],
      },
      {
        title: "System Health",
        url: "/system-health",
        icon: HeartPulse,
        roles: ["SUPER_ADMIN"],
      },
      {
        title: "Settings",
        url: "/settings",
        icon: Settings,
      },
    ],
  },
];
