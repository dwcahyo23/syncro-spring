import { ChevronDownIcon, ChevronUpIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export interface DataTableSortHeaderProps {
  title: string;
  field: string;
  sort: string;
  onSortChange: (sort: string) => void;
  className?: string;
}

export function DataTableSortHeader({
  title,
  field,
  sort,
  onSortChange,
  className,
}: DataTableSortHeaderProps) {
  const [currentField, currentDirection] = sort.split(",");
  const isActive = currentField === field;

  function toggleSort() {
    if (isActive) {
      onSortChange(`${field},${currentDirection === "asc" ? "desc" : "asc"}`);
    } else {
      onSortChange(`${field},asc`);
    }
  }

  return (
    <div className={cn("flex items-center", className)}>
      <Button
        variant="ghost"
        size="sm"
        className="-ml-3 h-8 data-[active=true]:font-bold"
        data-active={isActive}
        onClick={toggleSort}
      >
        <span>{title}</span>
        {isActive ? (
          currentDirection === "desc" ? (
            <ChevronDownIcon className="ml-2 size-4" />
          ) : (
            <ChevronUpIcon className="ml-2 size-4" />
          )
        ) : (
          <div className="ml-2 size-4 opacity-50 flex flex-col items-center justify-center -space-y-1">
            <ChevronUpIcon className="size-3" />
            <ChevronDownIcon className="size-3" />
          </div>
        )}
      </Button>
    </div>
  );
}
