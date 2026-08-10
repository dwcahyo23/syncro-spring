import { Badge } from "@/components/ui/badge";

type MachineSummaryCardProps = {
  code?: string;
  name?: string;
  machineGroupName?: string;
  status?: string;
};

export function MachineSummaryCard({ code, name, machineGroupName, status }: MachineSummaryCardProps) {
  const isActive = status === "ACTIVE";
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <div className="flex min-w-0 items-center justify-between gap-2">
        <p className="truncate font-semibold text-sm" title={name ?? code}>
          {name ?? code ?? "Unknown machine"}
        </p>
        <Badge
          aria-label={`Manual status: ${isActive ? "Active" : "Inactive"}`}
          variant={isActive ? "secondary" : "outline"}
        >
          {isActive ? "Active" : "Inactive"}
        </Badge>
      </div>
      <p className="truncate text-muted-foreground text-xs">
        {code}
        {machineGroupName ? ` · ${machineGroupName}` : ""}
      </p>
    </div>
  );
}
