import {
  Activity,
  Bell,
  Database,
  FileText,
  HeartPulse,
  type LucideIcon,
  MessageSquare,
  Radio,
  Settings,
} from "lucide-react";

export interface NavSubItem {
  title: string;
  url: string;
  icon?: LucideIcon;
  comingSoon?: boolean;
  newTab?: boolean;
  isNew?: boolean;
}

export interface NavMainItem {
  title: string;
  url: string;
  icon?: LucideIcon;
  subItems?: NavSubItem[];
  comingSoon?: boolean;
  newTab?: boolean;
  isNew?: boolean;
  roles?: string[];
}

export interface NavGroup {
  id: number;
  label?: string;
  items: NavMainItem[];
}

export const sidebarItems: NavGroup[] = [
  {
    id: 1,
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
      {
        title: "Master Data",
        url: "/master-data",
        icon: Database,
        subItems: [
          { title: "Plants", url: "/master-data/plants" },
          { title: "Machine Groups", url: "/master-data/machine-groups" },
          { title: "Machines", url: "/master-data/machines" },
          { title: "Spareparts", url: "/master-data/spareparts" },
          { title: "Installations", url: "/master-data/installations" },
          { title: "Responsibility", url: "/master-data/responsibility" },
        ],
      },
      {
        title: "WAHA Templates",
        url: "/waha-templates",
        icon: MessageSquare,
      },
      {
        title: "Audit Log",
        url: "/audit-log",
        icon: FileText,
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
