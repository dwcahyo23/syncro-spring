"use client";

import { useEffect, useMemo, useRef, useState } from "react";

import { zodResolver } from "@hookform/resolvers/zod";
import { Controller, useForm } from "react-hook-form";
import { z } from "zod";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useKpiTargets, useUpsertKpiTarget } from "@/features/analytics/hooks/use-kpi-targets";
import { usePlantScope } from "@/features/plant-scope/plant-scope-store";
import { useAuthUser } from "@/lib/auth/use-auth-user";

/**
 * KPI target configuration dialog (story 20-2). Writes through the 20-1
 * {@code PUT /api/v1/kpi/targets} upsert — role-gated server-side
 * (SUPER_ADMIN/MANAGER_MAINTENANCE) and audit-logged there; this component only
 * hides the trigger for roles the backend rejects (visibility is UX, not security).
 * Zod ranges mirror the backend DTO validation (@PositiveOrZero / 0..100 percents);
 * an empty field sends null, which the 20-1 partial-update semantics keep as the
 * stored value. Non-native shadcn/Radix controls per the project UI rule.
 */

const optionalNumber = (max: number | null) =>
  z
    .string()
    .trim()
    .transform((value) => (value === "" ? null : Number(value)))
    .refine((value) => value === null || (Number.isFinite(value) && value >= 0 && (max === null || value <= max)), {
      message: max === null ? "Must be a number of 0 or more." : `Must be a number between 0 and ${max}.`,
    });

const targetFormSchema = z
  .object({
    monthlyBreakdownTarget: optionalNumber(null),
    mtbfTargetDays: optionalNumber(null),
    mttrTargetMinutes: optionalNumber(null),
    oeeQualityPercent: optionalNumber(100),
    oeePerformancePercent: optionalNumber(100),
  })
  // Backend column is Integer — reject fractions with the error pinned to the field
  // (a field-level .refine() lands at the root path and never reaches fieldState).
  .superRefine((values, ctx) => {
    if (values.monthlyBreakdownTarget !== null && !Number.isInteger(values.monthlyBreakdownTarget)) {
      ctx.addIssue({ code: "custom", path: ["monthlyBreakdownTarget"], message: "Must be a whole number." });
    }
  });

type TargetFormInput = {
  monthlyBreakdownTarget: string;
  mtbfTargetDays: string;
  mttrTargetMinutes: string;
  oeeQualityPercent: string;
  oeePerformancePercent: string;
};

const NUMBER_FIELDS = [
  {
    name: "monthlyBreakdownTarget",
    label: "Monthly breakdown target",
    hint: "Maximum breakdown workorders per month (lower is better).",
    step: "1",
  },
  { name: "mtbfTargetDays", label: "MTBF target (days)", hint: "Higher is better.", step: "0.01" },
  { name: "mttrTargetMinutes", label: "MTTR target (minutes)", hint: "Lower is better.", step: "0.01" },
  { name: "oeeQualityPercent", label: "OEE quality (%)", hint: "0–100. OEE baseline factor.", step: "0.01" },
  {
    name: "oeePerformancePercent",
    label: "OEE performance (%)",
    hint: "0–100. OEE baseline factor.",
    step: "0.01",
  },
] as const satisfies ReadonlyArray<{
  name: keyof TargetFormInput;
  label: string;
  hint: string;
  step: string;
}>;

function formatMonthLabel(monthKey: string): string {
  const [year, month] = monthKey.split("-");
  const date = new Date(Number(year), Number(month) - 1, 1);
  return date.toLocaleString(undefined, { month: "long", year: "numeric" });
}

export function KpiTargetDialog({ month }: { month: string }) {
  const user = useAuthUser();
  const canConfigure = user?.applicationRole === "SUPER_ADMIN" || user?.applicationRole === "MANAGER_MAINTENANCE";

  const { scope, activePlantId } = usePlantScope();
  const plants = scope?.availablePlants ?? [];
  const [open, setOpen] = useState(false);
  const [plantId, setPlantId] = useState<string>("");

  useEffect(() => {
    if (!plantId && plants.length > 0) {
      setPlantId(activePlantId && plants.some((p) => p.id === activePlantId) ? activePlantId : plants[0].id);
    }
  }, [plantId, plants, activePlantId]);

  const targetsQuery = useKpiTargets(plantId, canConfigure && open);
  const upsert = useUpsertKpiTarget();

  const existing = useMemo(() => targetsQuery.data?.find((t) => t.month === month) ?? null, [targetsQuery.data, month]);

  const form = useForm<TargetFormInput, unknown, z.output<typeof targetFormSchema>>({
    resolver: zodResolver(targetFormSchema),
    defaultValues: {
      monthlyBreakdownTarget: "",
      mtbfTargetDays: "",
      mttrTargetMinutes: "",
      oeeQualityPercent: "",
      oeePerformancePercent: "",
    },
  });

  // Prefill from the stored target once per open/plant/month after the query resolves;
  // null stored values render as empty fields ("leave empty to keep current value").
  // Guarded so a late-resolving fetch never clobbers values the user already typed.
  const prefilledFor = useRef<string | null>(null);
  useEffect(() => {
    if (!open) {
      prefilledFor.current = null;
      return;
    }
    if (targetsQuery.data === undefined) {
      return;
    }
    const key = `${plantId}:${month}`;
    if (prefilledFor.current === key) {
      return;
    }
    prefilledFor.current = key;
    form.reset({
      monthlyBreakdownTarget: existing?.monthlyBreakdownTarget?.toString() ?? "",
      mtbfTargetDays: existing?.mtbfTargetDays?.toString() ?? "",
      mttrTargetMinutes: existing?.mttrTargetMinutes?.toString() ?? "",
      oeeQualityPercent: existing?.oeeQualityPercent?.toString() ?? "",
      oeePerformancePercent: existing?.oeePerformancePercent?.toString() ?? "",
    });
  }, [open, plantId, month, targetsQuery.data, existing, form]);

  if (!canConfigure) {
    return null;
  }

  const onSubmit = form.handleSubmit((values) => {
    if (!plantId) {
      return;
    }
    upsert.mutate(
      {
        plantId,
        month,
        monthlyBreakdownTarget: values.monthlyBreakdownTarget,
        mtbfTargetDays: values.mtbfTargetDays,
        mttrTargetMinutes: values.mttrTargetMinutes,
        oeeQualityPercent: values.oeeQualityPercent,
        oeePerformancePercent: values.oeePerformancePercent,
      },
      { onSuccess: () => setOpen(false) },
    );
  });

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="outline" size="sm">
          Configure targets
        </Button>
      </DialogTrigger>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Configure KPI targets</DialogTitle>
          <DialogDescription>
            Targets for {formatMonthLabel(month)}. Values are validated server-side and every change is audit-logged.
            Leave a field empty to keep its current value.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={onSubmit} noValidate className="space-y-4">
          {targetsQuery.isError && (
            <p className="status-icon-warning text-destructive text-sm" role="alert">
              Failed to load the current targets — the fields below start empty. Saving still keeps any stored value for
              fields you leave empty.
            </p>
          )}

          <div className="space-y-1.5">
            <Label htmlFor="kpi-target-plant">Plant</Label>
            <Select value={plantId} onValueChange={setPlantId}>
              <SelectTrigger id="kpi-target-plant" className="w-full">
                <SelectValue placeholder="Select a plant" />
              </SelectTrigger>
              <SelectContent>
                {plants.map((plant) => (
                  <SelectItem key={plant.id} value={plant.id}>
                    {plant.code} — {plant.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {NUMBER_FIELDS.map((field) => (
            <div key={field.name} className="space-y-1.5">
              <Label htmlFor={`kpi-target-${field.name}`}>{field.label}</Label>
              <Controller
                control={form.control}
                name={field.name}
                render={({ field: input, fieldState }) => (
                  <>
                    <Input
                      id={`kpi-target-${field.name}`}
                      type="number"
                      min={0}
                      step={field.step}
                      inputMode="decimal"
                      value={input.value}
                      onChange={(event) => input.onChange(event.target.value)}
                      onBlur={input.onBlur}
                      ref={input.ref}
                      aria-invalid={fieldState.invalid}
                      aria-describedby={`kpi-target-${field.name}-hint`}
                    />
                    <p id={`kpi-target-${field.name}-hint`} className="text-muted-foreground text-xs">
                      {fieldState.error?.message ?? field.hint}
                    </p>
                  </>
                )}
              />
            </div>
          ))}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={upsert.isPending}>
              {upsert.isPending ? "Saving…" : "Save target"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
