declare module "lucide-react" {
  import type { ComponentType, SVGProps } from "react";

  export type LucideIcon = ComponentType<SVGProps<SVGSVGElement>>;
  export const Activity: LucideIcon;
  export const BadgeCheck: LucideIcon;
  export const Bell: LucideIcon;
  export const Check: LucideIcon;
  export const CheckIcon: LucideIcon;
  export const ChevronDown: LucideIcon;
  export const ChevronDownIcon: LucideIcon;
  export const ChevronLeft: LucideIcon;
  export const ChevronLeftIcon: LucideIcon;
  export const ChevronRight: LucideIcon;
  export const ChevronRightIcon: LucideIcon;
  export const ChevronUp: LucideIcon;
  export const ChevronUpIcon: LucideIcon;
  export const ChevronsUpDown: LucideIcon;
  export const Circle: LucideIcon;
  export const CircleCheck: LucideIcon;
  export const CircleCheckIcon: LucideIcon;
  export const CircleDashed: LucideIcon;
  export const CircleSlash: LucideIcon;
  export const CircleUser: LucideIcon;
  export const Clock: LucideIcon;
  export const Command: LucideIcon;
  export const CreditCard: LucideIcon;
  export const Database: LucideIcon;
  export const Ellipsis: LucideIcon;
  export const EllipsisVertical: LucideIcon;
  export const FileText: LucideIcon;
  export const Folder: LucideIcon;
  export const Forward: LucideIcon;
  export const GalleryVerticalEnd: LucideIcon;
  export const Gauge: LucideIcon;
  export const Globe: LucideIcon;
  export const Hash: LucideIcon;
  export const HeartPulse: LucideIcon;
  export const InfoIcon: LucideIcon;
  export const LayoutDashboard: LucideIcon;
  export const Loader2Icon: LucideIcon;
  export const LoaderCircle: LucideIcon;
  export const Lock: LucideIcon;
  export const LogOut: LucideIcon;
  export const MailIcon: LucideIcon;
  export const MessageSquare: LucideIcon;
  export const MessageSquareDot: LucideIcon;
  export const Minus: LucideIcon;
  export const MinusIcon: LucideIcon;
  export const Monitor: LucideIcon;
  export const Moon: LucideIcon;
  export const MoreHorizontal: LucideIcon;
  export const MoreHorizontalIcon: LucideIcon;
  export const OctagonXIcon: LucideIcon;
  export const PanelLeft: LucideIcon;
  export const PanelLeftIcon: LucideIcon;
  export const Plus: LucideIcon;
  export const PlusCircleIcon: LucideIcon;
  export const Radio: LucideIcon;
  export const RefreshCw: LucideIcon;
  export const Search: LucideIcon;
  export const SearchIcon: LucideIcon;
  export const Settings: LucideIcon;
  export const SlidersHorizontal: LucideIcon;
  export const Sparkles: LucideIcon;
  export const Sun: LucideIcon;
  export const Trash2: LucideIcon;
  export const TriangleAlertIcon: LucideIcon;
  export const User: LucideIcon;
  export const X: LucideIcon;
  export const XIcon: LucideIcon;
}

declare module "simple-icons" {
  export type SimpleIcon = { title: string; path: string };
}

declare module "react-hook-form" {
  export type FieldError = { message?: string };
  export type FieldState = { invalid: boolean; error?: FieldError };
  export type ControllerRenderProps = {
    name: string;
    value: string | number | readonly string[] | undefined;
    onChange: (value: unknown) => void;
    onBlur: () => void;
    ref: (instance: unknown) => void;
  };
  export function useForm<T>(options?: unknown): {
    control: unknown;
    handleSubmit: (handler: (data: T) => void) => (event?: unknown) => void;
  };
  export function Controller(props: {
    control: unknown;
    name: string;
    render: (context: { field: ControllerRenderProps; fieldState: FieldState }) => React.ReactNode;
  }): React.ReactNode;
}
