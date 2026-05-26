"use client";

import { ChevronDown, Gauge } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

export type PlantScopeOption = {
  id: string;
  code: string;
  name: string;
};

type PlantScopeValue = string | "all";

type PlantScopeSelectorProps = {
  availablePlants: PlantScopeOption[];
  activePlantId: PlantScopeValue;
  onSelect: (plantId: PlantScopeValue) => void;
  isSuperAdmin: boolean;
  emptyReason?: string | null;
  className?: string;
};

export function PlantScopeSelector({
  availablePlants,
  activePlantId,
  onSelect,
  isSuperAdmin,
  emptyReason,
  className,
}: PlantScopeSelectorProps) {
  if (availablePlants.length === 0) {
    const helperText = isSuperAdmin
      ? "Add plants in Master Data"
      : emptyReason === "NO_PLANTS_ASSIGNED"
        ? "No plants assigned. Contact your administrator."
        : "No plants assigned. Contact your administrator.";

    return (
      <TooltipProvider>
        <Tooltip>
          <TooltipTrigger asChild>
            <span
              aria-label="Select plant scope"
              aria-disabled="true"
              className={cn(
                "inline-flex h-8 items-center gap-2 rounded-lg border bg-muted/40 px-3 text-muted-foreground text-sm",
                className,
              )}
              role="status"
            >
              <Gauge className="size-4" aria-hidden="true" />
              <span>{isSuperAdmin ? "All Plants" : "No plants assigned"}</span>
            </span>
          </TooltipTrigger>
          <TooltipContent>{helperText}</TooltipContent>
        </Tooltip>
      </TooltipProvider>
    );
  }

  if (availablePlants.length === 1) {
    const plant = availablePlants[0];

    return (
      <span
        aria-label="Select plant scope"
        className={cn("inline-flex h-8 items-center gap-2 rounded-lg border bg-background px-3 text-sm", className)}
        role="status"
      >
        <Gauge className="size-4 text-muted-foreground" aria-hidden="true" />
        <span className="font-medium">{plant.name}</span>
        <span className="text-muted-foreground">{plant.code}</span>
      </span>
    );
  }

  const allLabel = isSuperAdmin ? "All Plants" : "All my plants";
  const activePlant = availablePlants.find((plant) => plant.id === activePlantId);
  const triggerLabel = activePlant?.name ?? allLabel;

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          aria-label="Select plant scope"
          className={cn("justify-between border bg-background font-normal", className)}
          size="sm"
          type="button"
          variant="outline"
        >
          <span className="inline-flex min-w-0 items-center gap-2">
            <Gauge className="size-4 text-muted-foreground" aria-hidden="true" />
            <span className="truncate">{triggerLabel}</span>
          </span>
          <ChevronDown className="size-4 text-muted-foreground" aria-hidden="true" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-56">
        <DropdownMenuLabel>Plant scope</DropdownMenuLabel>
        <DropdownMenuRadioGroup value={activePlantId} onValueChange={onSelect}>
          <DropdownMenuRadioItem value="all">{allLabel}</DropdownMenuRadioItem>
          {availablePlants.map((plant) => (
            <DropdownMenuRadioItem key={plant.id} value={plant.id}>
              <span className="flex min-w-0 flex-col">
                <span className="truncate font-medium">{plant.name}</span>
                <span className="truncate text-muted-foreground text-xs">{plant.code}</span>
              </span>
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
