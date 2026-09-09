"use client";

import { useEffect, useMemo, useRef, useState } from "react";

import { zodResolver } from "@hookform/resolvers/zod";
import { useFormatter, useTranslations } from "next-intl";
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
 * Validation copy is locale-aware: the schema is built per render (rule 23-2-9).
 */

type TargetFormInput = {
  monthlyBreakdownTarget: string;
  mtbfTargetDays: string;
  mttrTargetMinutes: string;
  oeeQualityPercent: string;
  oeePerformancePercent: string;
};

const NUMBER_FIELDS = [
  { name: "monthlyBreakdownTarget", step: "1" },
  { name: "mtbfTargetDays", step: "0.01" },
  { name: "mttrTargetMinutes", step: "0.01" },
  { name: "oeeQualityPercent", step: "0.01" },
  { name: "oeePerformancePercent", step: "0.01" },
] as const satisfies ReadonlyArray<{ name: keyof TargetFormInput; step: string }>;

export function KpiTargetDialog({ month }: { month: string }) {
  const t = useTranslations("analytics");
  const tc = useTranslations("common");
  const format = useFormatter();
  const user = useAuthUser();
  const canConfigure = user?.applicationRole === "SUPER_ADMIN" || user?.applicationRole === "MANAGER_MAINTENANCE";

  const { scope, activePlantId } = usePlantScope();
  const plants = scope?.availablePlants ?? [];
  const [open, setOpen] = useState(false);
  const [plantId, setPlantId] = useState<string>("");

  // Zod messages depend on the active locale — rebuild the schema when `t` changes.
  const targetFormSchema = useMemo(() => {
    const optionalNumber = (max: number | null) =>
      z
        .string()
        .trim()
        .transform((value) => (value === "" ? null : Number(value)))
        .refine((value) => value === null || (Number.isFinite(value) && value >= 0 && (max === null || value <= max)), {
          message:
            max === null
              ? t("targetDialog.validation.numberMinZero")
              : t("targetDialog.validation.numberRange", { max }),
        });

    return (
      z
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
            ctx.addIssue({
              code: "custom",
              path: ["monthlyBreakdownTarget"],
              message: t("targetDialog.validation.wholeNumber"),
            });
          }
        })
    );
  }, [t]);

  // Same Intl options as the previous toLocaleString call — en output stays
  // byte-identical ("September 2026"); id renders "September 2026" in the id-ID locale.
  function formatMonthLabel(monthKey: string): string {
    const [year, monthPart] = monthKey.split("-");
    const date = new Date(Number(year), Number(monthPart) - 1, 1);
    return format.dateTime(date, { month: "long", year: "numeric" });
  }

  useEffect(() => {
    if (!plantId && plants.length > 0) {
      setPlantId(activePlantId && plants.some((p) => p.id === activePlantId) ? activePlantId : plants[0].id);
    }
  }, [plantId, plants, activePlantId]);

  const targetsQuery = useKpiTargets(plantId, canConfigure && open);
  const upsert = useUpsertKpiTarget();

  const existing = useMemo(
    () => targetsQuery.data?.find((target) => target.month === month) ?? null,
    [targetsQuery.data, month],
  );

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
          {t("targetDialog.trigger")}
        </Button>
      </DialogTrigger>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{t("targetDialog.title")}</DialogTitle>
          <DialogDescription>{t("targetDialog.description", { month: formatMonthLabel(month) })}</DialogDescription>
        </DialogHeader>

        <form onSubmit={onSubmit} noValidate className="space-y-4">
          {targetsQuery.isError && (
            <p className="status-icon-warning text-destructive text-sm" role="alert">
              {t("targetDialog.loadError")}
            </p>
          )}

          <div className="space-y-1.5">
            <Label htmlFor="kpi-target-plant">{tc("plant")}</Label>
            <Select value={plantId} onValueChange={setPlantId}>
              <SelectTrigger id="kpi-target-plant" className="w-full">
                <SelectValue placeholder={t("targetDialog.selectPlant")} />
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
              <Label htmlFor={`kpi-target-${field.name}`}>{t(`targetDialog.fields.${field.name}.label`)}</Label>
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
                      {fieldState.error?.message ?? t(`targetDialog.fields.${field.name}.hint`)}
                    </p>
                  </>
                )}
              />
            </div>
          ))}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setOpen(false)}>
              {tc("cancel")}
            </Button>
            <Button type="submit" disabled={upsert.isPending}>
              {upsert.isPending ? t("targetDialog.saving") : t("targetDialog.save")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
