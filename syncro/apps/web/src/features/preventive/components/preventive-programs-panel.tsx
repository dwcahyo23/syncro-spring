"use client";

import { useState } from "react";

import { useTranslations } from "next-intl";

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

const CATEGORIES: readonly PreventiveCategory[] = ["MECHANICAL", "ELECTRICAL"];
const SCHEDULE_TYPES: readonly ScheduleType[] = ["MONTHLY", "ANNUAL"];

/**
 * Preventive programs list + create form (story 11-1, FR-130). Machine is chosen via a
 * shadcn Select (label = code · name · plant), never a raw UUID. Category and schedule
 * type use shadcn Select. Scope/permission enforcement is server-side.
 */
export function PreventiveProgramsPanel() {
  const t = useTranslations("preventive");
  const tc = useTranslations("common");
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
          <CardTitle className="text-base">{t("programs.newProgram")}</CardTitle>
          <CardDescription>{t("programs.newProgramDescription")}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-2">
              <Label htmlFor="machine">{t("machine")}</Label>
              <Select value={machineId || undefined} onValueChange={setMachineId}>
                <SelectTrigger aria-label={t("machine")} disabled={isLoadingMachines}>
                  <SelectValue
                    placeholder={isLoadingMachines ? t("programs.loadingMachines") : t("programs.selectMachine")}
                  />
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
              <Label htmlFor="title">{t("programs.title")}</Label>
              <Input
                id="title"
                value={title}
                onChange={(event) => setTitle(event.target.value)}
                placeholder={t("programs.titlePlaceholder")}
              />
            </div>
            <div className="space-y-2">
              <Label>{t("programs.category")}</Label>
              <Select value={category} onValueChange={(v) => setCategory(v as PreventiveCategory)}>
                <SelectTrigger aria-label={t("programs.category")}>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {CATEGORIES.map((code) => (
                    <SelectItem key={code} value={code}>
                      {t(`category.${code}`)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label>{t("programs.scheduleType")}</Label>
              <Select value={scheduleType} onValueChange={(v) => setScheduleType(v as ScheduleType)}>
                <SelectTrigger aria-label={t("programs.scheduleType")}>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {SCHEDULE_TYPES.map((code) => (
                    <SelectItem key={code} value={code}>
                      {t(`scheduleType.${code}`)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="day">{t("programs.dayOfMonth")}</Label>
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
                <Label htmlFor="month">{t("programs.monthOfYear")}</Label>
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
              <Label htmlFor="description">{t("programs.descriptionOptional")}</Label>
              <Input
                id="description"
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                placeholder={t("programs.descriptionPlaceholder")}
              />
            </div>
            <div className="flex items-center justify-between sm:col-span-2">
              <div>
                <Label htmlFor="auto-workorder">{t("programs.autoWorkorderLabel")}</Label>
                <p className="text-muted-foreground text-xs">{t("programs.autoWorkorderHint")}</p>
              </div>
              <Switch id="auto-workorder" checked={autoWorkorder} onCheckedChange={setAutoWorkorder} />
            </div>
          </div>
          <Button
            type="button"
            disabled={createProgram.isPending || !machineId || !title.trim()}
            onClick={handleCreate}
          >
            {createProgram.isPending ? t("programs.creating") : t("programs.createProgram")}
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
          <p className="text-muted-foreground text-sm">{t("programs.loadFailed")}</p>
          <Button type="button" variant="outline" size="sm" onClick={() => void refetch()}>
            {tc("retry")}
          </Button>
        </div>
      ) : (programs ?? []).length === 0 ? (
        <Card>
          <CardContent className="p-6">
            <p className="text-muted-foreground text-sm">{t("programs.empty")}</p>
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
  const t = useTranslations("preventive");
  const tc = useTranslations("common");
  return (
    <div className="flex items-center justify-between gap-2 rounded-lg border p-3">
      <div className="min-w-0">
        <p className="font-medium text-sm">{program.title}</p>
        <p className="text-muted-foreground text-xs">
          {program.category} · {program.scheduleType} · {t("programs.day", { day: program.dayOfMonth })}
          {program.monthOfYear != null ? t("programs.month", { month: program.monthOfYear }) : ""} ·{" "}
          {program.active ? t("programs.active") : t("programs.inactive")} ·{" "}
          {program.autoWorkorder ? t("programs.autoWorkorder") : t("programs.noAutoWorkorder")}
        </p>
      </div>
      <Button type="button" variant="ghost" size="sm" onClick={onDelete}>
        {tc("delete")}
      </Button>
    </div>
  );
}
