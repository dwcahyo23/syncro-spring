"use client";

import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import {
  useCreatePreventiveProgram,
  useDeletePreventiveProgram,
  usePreventivePrograms,
} from "@/features/preventive/hooks/use-preventive";
import type { PreventiveCategory, PreventiveProgramView, ScheduleType } from "@/features/preventive/types";

/**
 * Preventive programs list + create form (story 11-1, FR-130). Creating a program
 * immediately generates its schedule window server-side. Scope/permission enforcement
 * is server-side; the form is shown to all authenticated users.
 */
export function PreventiveProgramsPanel() {
  const { data: programs, isLoading, isError, refetch } = usePreventivePrograms();
  const createProgram = useCreatePreventiveProgram();
  const deleteProgram = useDeletePreventiveProgram();

  const [machineId, setMachineId] = useState("");
  const [category, setCategory] = useState<PreventiveCategory>("MECHANICAL");
  const [scheduleType, setScheduleType] = useState<ScheduleType>("MONTHLY");
  const [dayOfMonth, setDayOfMonth] = useState(1);
  const [monthOfYear, setMonthOfYear] = useState(1);
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");

  const handleCreate = () => {
    createProgram.mutate({
      machineId,
      category,
      scheduleType,
      dayOfMonth,
      monthOfYear: scheduleType === "ANNUAL" ? monthOfYear : null,
      title,
      description: description || null,
    });
  };

  return (
    <div className="space-y-4">
      {/* Create form */}
      <div className="space-y-3 rounded-lg border p-4">
        <h3 className="font-medium text-sm">New preventive program</h3>
        <div className="grid gap-3 sm:grid-cols-2">
          <input
            value={machineId}
            onChange={(event) => setMachineId(event.target.value)}
            placeholder="Machine id (UUID)"
            aria-label="Machine id"
            className="h-9 rounded-md border bg-transparent px-3 text-sm"
          />
          <input
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            placeholder="Title"
            aria-label="Title"
            className="h-9 rounded-md border bg-transparent px-3 text-sm"
          />
          <select
            value={category}
            onChange={(event) => setCategory(event.target.value as PreventiveCategory)}
            aria-label="Category"
            className="h-9 rounded-md border bg-transparent px-3 text-sm"
          >
            <option value="MECHANICAL">Mechanical</option>
            <option value="ELECTRICAL">Electrical</option>
          </select>
          <select
            value={scheduleType}
            onChange={(event) => setScheduleType(event.target.value as ScheduleType)}
            aria-label="Schedule type"
            className="h-9 rounded-md border bg-transparent px-3 text-sm"
          >
            <option value="MONTHLY">Monthly</option>
            <option value="ANNUAL">Annual</option>
          </select>
          <input
            type="number"
            min={1}
            max={31}
            value={dayOfMonth}
            onChange={(event) => setDayOfMonth(Number(event.target.value))}
            aria-label="Day of month"
            className="h-9 rounded-md border bg-transparent px-3 text-sm"
          />
          {scheduleType === "ANNUAL" ? (
            <input
              type="number"
              min={1}
              max={12}
              value={monthOfYear}
              onChange={(event) => setMonthOfYear(Number(event.target.value))}
              aria-label="Month of year"
              className="h-9 rounded-md border bg-transparent px-3 text-sm"
            />
          ) : null}
          <input
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            placeholder="Description (optional)"
            aria-label="Description"
            className="h-9 rounded-md border bg-transparent px-3 text-sm sm:col-span-2"
          />
        </div>
        <Button
          type="button"
          size="sm"
          disabled={createProgram.isPending || !machineId.trim() || !title.trim()}
          onClick={handleCreate}
        >
          {createProgram.isPending ? "Creating…" : "Create program"}
        </Button>
      </div>

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
        <div className="rounded-lg border p-6">
          <p className="text-muted-foreground text-sm">No preventive programs yet. Create one above.</p>
        </div>
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
          {program.active ? "active" : "inactive"}
        </p>
      </div>
      <Button type="button" variant="ghost" size="sm" onClick={onDelete}>
        Delete
      </Button>
    </div>
  );
}
