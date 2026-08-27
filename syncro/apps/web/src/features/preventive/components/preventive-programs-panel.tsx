"use client";

import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { usePlantScope } from "@/features/plant-scope/plant-scope-store";
import {
  useCreatePreventiveProgram,
  useDeletePreventiveProgram,
  usePreventivePrograms,
} from "@/features/preventive/hooks/use-preventive";
import type { PreventiveCategory, PreventiveProgramView, ScheduleType } from "@/features/preventive/types";
import { useListMachines } from "@/lib/api/generated/syncro";

/**
 * Preventive programs list + create form (story 11-1, FR-130). Machine is chosen via a
 * shadcn Select (label = code · name · plant), never a raw UUID. Category and schedule
 * type use shadcn Select. Scope/permission enforcement is server-side.
 */
export function PreventiveProgramsPanel() {
  const { data: programs, isLoading, isError, refetch } = usePreventivePrograms();
  const createProgram = useCreatePreventiveProgram();
  const deleteProgram = useDeletePreventiveProgram();
  const plantScope = usePlantScope();
  const plantId = plantScope.activePlantId === "all" ? undefined : plantScope.activePlantId;

  const { data: machinesRes, isLoading: isLoadingMachines } = useListMachines({
    plantId,
    page: 0,
    size: 100,
  });

  const [machineId, setMachineId] = useState("");
  const [category, setCategory] = useState<PreventiveCategory>("MECHANICAL");
  const [scheduleType, setScheduleType] = useState<ScheduleType>("MONTHLY");
  const [dayOfMonth, setDayOfMonth] = useState(1);
  const [monthOfYear, setMonthOfYear] = useState(1);
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [autoWorkorder, setAutoWorkorder] = useState(false);

  const handleCreate = () => {
    createProgram.mutate({
      machineId,
      category,
      scheduleType,
      dayOfMonth,
      monthOfYear: scheduleType === "ANNUAL" ? monthOfYear : null,
      title,
      description: description || null,
      autoWorkorder,
    });
  };

  return (
    <div className="space-y-4">
      {/* Create form */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">New preventive program</CardTitle>
          <CardDescription>Create a recurring maintenance program for a machine.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-2">
              <Label htmlFor="machine">Machine</Label>
              <Select value={machineId || undefined} onValueChange={setMachineId}>
                <SelectTrigger aria-label="Machine" disabled={isLoadingMachines}>
                  <SelectValue placeholder={isLoadingMachines ? "Loading machines..." : "Select machine"} />
                </SelectTrigger>
                <SelectContent>
                  {machinesRes?.data?.items?.map((m) => (
                    <SelectItem key={m.id} value={m.id ?? ""}>
                      {m.code} · {m.name} · {m.plantCode}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="title">Title</Label>
              <Input
                id="title"
                value={title}
                onChange={(event) => setTitle(event.target.value)}
                placeholder="e.g. Monthly lube check"
              />
            </div>
            <div className="space-y-2">
              <Label>Category</Label>
              <Select value={category} onValueChange={(v) => setCategory(v as PreventiveCategory)}>
                <SelectTrigger aria-label="Category">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="MECHANICAL">Mechanical</SelectItem>
                  <SelectItem value="ELECTRICAL">Electrical</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label>Schedule type</Label>
              <Select value={scheduleType} onValueChange={(v) => setScheduleType(v as ScheduleType)}>
                <SelectTrigger aria-label="Schedule type">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="MONTHLY">Monthly</SelectItem>
                  <SelectItem value="ANNUAL">Annual</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="day">Day of month</Label>
              <Input
                id="day"
                type="number"
                min={1}
                max={31}
                value={dayOfMonth}
                onChange={(event) => setDayOfMonth(Number(event.target.value))}
              />
            </div>
            {scheduleType === "ANNUAL" ? (
              <div className="space-y-2">
                <Label htmlFor="month">Month of year</Label>
                <Input
                  id="month"
                  type="number"
                  min={1}
                  max={12}
                  value={monthOfYear}
                  onChange={(event) => setMonthOfYear(Number(event.target.value))}
                />
              </div>
            ) : null}
            <div className="space-y-2 sm:col-span-2">
              <Label htmlFor="description">Description (optional)</Label>
              <Input
                id="description"
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                placeholder="Optional description"
              />
            </div>
            <div className="flex items-center justify-between sm:col-span-2">
              <div>
                <Label htmlFor="auto-workorder">Auto-create workorder on approval</Label>
                <p className="text-muted-foreground text-xs">
                  When a schedule is approved, an internal preventive workorder is created.
                </p>
              </div>
              <Switch id="auto-workorder" checked={autoWorkorder} onCheckedChange={setAutoWorkorder} />
            </div>
          </div>
          <Button
            type="button"
            disabled={createProgram.isPending || !machineId || !title.trim()}
            onClick={handleCreate}
          >
            {createProgram.isPending ? "Creating…" : "Create program"}
          </Button>
        </CardContent>
      </Card>

      {/* Program list */}
      {isLoading ? (
        <div className="space-y-2">
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-10 w-full" />
        </div>
      ) : isError ? (
        <div className="flex flex-col items-center gap-3 rounded-lg border p-6">
          <p className="text-muted-foreground text-sm">Failed to load preventive programs.</p>
          <Button type="button" variant="outline" size="sm" onClick={() => void refetch()}>
            Retry
          </Button>
        </div>
      ) : (programs ?? []).length === 0 ? (
        <Card>
          <CardContent className="p-6">
            <p className="text-muted-foreground text-sm">No preventive programs yet. Create one above.</p>
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-2">
          {(programs ?? []).map((program) => (
            <ProgramRow key={program.id} program={program} onDelete={() => deleteProgram.mutate(program.id)} />
          ))}
        </div>
      )}
    </div>
  );
}

function ProgramRow({ program, onDelete }: { program: PreventiveProgramView; onDelete: () => void }) {
  return (
    <div className="flex items-center justify-between gap-2 rounded-lg border p-3">
      <div className="min-w-0">
        <p className="font-medium text-sm">{program.title}</p>
        <p className="text-muted-foreground text-xs">
          {program.category} · {program.scheduleType} · day {program.dayOfMonth}
          {program.monthOfYear != null ? ` / month ${program.monthOfYear}` : ""} ·{" "}
          {program.active ? "active" : "inactive"} · {program.autoWorkorder ? "auto-workorder" : "no auto-workorder"}
        </p>
      </div>
      <Button type="button" variant="ghost" size="sm" onClick={onDelete}>
        Delete
      </Button>
    </div>
  );
}
