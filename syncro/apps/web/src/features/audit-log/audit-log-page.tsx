"use client";

import { useMemo, useState } from "react";

import { TriangleAlertIcon, XIcon } from "lucide-react";
import { useTranslations } from "next-intl";

import { AuditLogTable } from "@/components/syncro/audit-log-table";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { DataTablePagination } from "@/components/ui/data-table-pagination";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { usePlantScope } from "@/features/plant-scope/plant-scope-store";
import type { ListAuditLogEntriesEntityType, ListAuditLogEntriesParams, PlantView } from "@/lib/api/generated/model";
import { ListAuditLogEntriesEntityType as EntityTypeValues } from "@/lib/api/generated/model";
import { useListAuditLogEntries, useListPlants } from "@/lib/api/generated/syncro";
import { useAuthUser } from "@/lib/auth/use-auth-user";

import { DecisionLogTab } from "./decision-log-tab";

type EntityFilter = "ALL" | ListAuditLogEntriesEntityType;
type PlantFilter = "ALL" | string;

export function AuditLogPage() {
  const t = useTranslations("auditLog");
  const tc = useTranslations("common");
  const user = useAuthUser();
  const canReadDecisions = user?.applicationRole === "SUPER_ADMIN" || user?.applicationRole === "AUDITOR";
  const plantScope = usePlantScope();
  const scope = plantScope.scope;
  const isAssignedEmpty = scope?.mode === "EMPTY";
  const plants = useListPlants({ query: { enabled: Boolean(scope) && !isAssignedEmpty } });
  const plantItems = plants.data?.data.items ?? [];
  const availablePlants = useMemo(() => permittedPlants(plantItems, scope), [plantItems, scope]);
  const [entityType, setEntityType] = useState<EntityFilter>("ALL");
  const [actor, setActor] = useState("");
  const [plantId, setPlantId] = useState<PlantFilter>("ALL");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const [sort, setSort] = useState("createdAt,desc");

  const hasFilters = entityType !== "ALL" || actor.trim() !== "" || plantId !== "ALL" || from !== "" || to !== "";

  const entityTypeOptions = useMemo(
    () => Object.values(EntityTypeValues).map((value) => ({ value, label: t(`entityTypes.${value}`) })),
    [t],
  );

  const params = {
    entityType: entityType === "ALL" ? undefined : entityType,
    actor: actor.trim() || undefined,
    plantId: plantId === "ALL" ? undefined : plantId,
    from: from ? `${from}T00:00:00.000Z` : undefined,
    to: to ? `${to}T23:59:59.999Z` : undefined,
    page,
    size,
    sort,
  } satisfies ListAuditLogEntriesParams;

  const entries = useListAuditLogEntries(params, {
    query: {
      enabled: Boolean(scope),
      queryKey: ["audit-log", scope?.mode, entityType, actor.trim(), plantId, from, to, page, size, sort],
    },
  });
  const items = entries.data?.data.items ?? [];
  const plantNameById = useMemo(() => {
    const map: Record<string, string> = {};
    for (const plant of plantItems) {
      if (plant.id) {
        map[plant.id] = `${plant.code} · ${plant.name}`;
      }
    }
    return map;
  }, [plantItems]);

  function resetFilters() {
    setEntityType("ALL");
    setActor("");
    setPlantId("ALL");
    setFrom("");
    setTo("");
    setPage(0);
  }

  return (
    <Tabs defaultValue="audit-log" className="space-y-4">
      <TabsList className="w-full justify-start overflow-x-auto">
        <TabsTrigger value="audit-log">{t("tabs.audit")}</TabsTrigger>
        {canReadDecisions ? <TabsTrigger value="decision-log">{t("tabs.decision")}</TabsTrigger> : null}
      </TabsList>
      <TabsContent value="audit-log">
        <Card>
          <CardHeader>
            <CardTitle>{t("title")}</CardTitle>
            <CardDescription>{t("description")}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid gap-3 rounded-lg border p-3 sm:grid-cols-[repeat(auto-fill,minmax(13rem,13rem))] sm:justify-start">
              <div className="grid min-w-0 gap-2">
                <Label htmlFor="audit-entity-type">{t("filters.entityType")}</Label>
                <Select
                  value={entityType}
                  onValueChange={(value) => {
                    setEntityType(value as EntityFilter);
                    setPage(0);
                  }}
                >
                  <SelectTrigger id="audit-entity-type" className="w-full min-w-0">
                    <SelectValue placeholder={t("filters.entityType")} />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="ALL">{t("filters.allEntityTypes")}</SelectItem>
                    {entityTypeOptions.map((option) => (
                      <SelectItem key={option.value} value={option.value}>
                        {option.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="grid min-w-0 gap-2">
                <Label htmlFor="audit-actor">{t("filters.actor")}</Label>
                <Input
                  id="audit-actor"
                  value={actor}
                  onChange={(event) => {
                    setActor(event.target.value);
                    setPage(0);
                  }}
                  placeholder={t("filters.actorPlaceholder")}
                  className="w-full min-w-0"
                />
              </div>
              <div className="grid min-w-0 gap-2">
                <Label htmlFor="audit-plant">{tc("plant")}</Label>
                <Select
                  value={plantId}
                  onValueChange={(value) => {
                    setPlantId(value);
                    setPage(0);
                  }}
                  disabled={isAssignedEmpty || plants.isLoading}
                >
                  <SelectTrigger id="audit-plant" className="w-full min-w-0">
                    <SelectValue placeholder={tc("plant")} />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="ALL">{t("filters.allPlants")}</SelectItem>
                    {availablePlants.map((plant) => (
                      <SelectItem key={plant.id ?? plant.code} value={plant.id ?? ""}>
                        {plant.code} · {plant.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <DatePickerField
                id="audit-from"
                label={t("filters.from")}
                value={from}
                onChange={(value) => {
                  setFrom(value);
                  setPage(0);
                }}
              />
              <DatePickerField
                id="audit-to"
                label={t("filters.to")}
                value={to}
                onChange={(value) => {
                  setTo(value);
                  setPage(0);
                }}
              />
              {hasFilters ? (
                <Button variant="outline" className="self-end" onClick={resetFilters}>
                  <XIcon />
                  {t("filters.reset")}
                </Button>
              ) : null}
            </div>

            {entries.isLoading ? <AuditLogSkeleton /> : null}
            {entries.isError ? (
              <AuditLogState
                title={t("loadError.title")}
                description={t("loadError.description")}
                action={
                  <Button variant="outline" onClick={() => void entries.refetch()}>
                    {tc("retry")}
                  </Button>
                }
              />
            ) : null}
            {!entries.isLoading && !entries.isError && items.length === 0 ? (
              <AuditLogState
                title={hasFilters ? t("empty.noMatch") : t("empty.none")}
                description={hasFilters ? t("empty.noMatchDescription") : t("empty.description")}
                action={
                  hasFilters ? (
                    <Button variant="outline" onClick={resetFilters}>
                      {t("filters.reset")}
                    </Button>
                  ) : undefined
                }
              />
            ) : null}
            {!entries.isLoading && !entries.isError && items.length > 0 ? (
              <AuditLogTable
                entries={items}
                plantNameById={plantNameById}
                sort={sort}
                onSortChange={(value) => {
                  setSort(value);
                  setPage(0);
                }}
              />
            ) : null}
            {!entries.isLoading && !entries.isError && entries.data?.data ? (
              <DataTablePagination
                page={page}
                size={size}
                totalElements={entries.data.data.totalElements}
                onPageChange={setPage}
                onSizeChange={(newSize) => {
                  setSize(newSize);
                  setPage(0);
                }}
              />
            ) : null}
          </CardContent>
        </Card>
      </TabsContent>
      {canReadDecisions ? (
        <TabsContent value="decision-log">
          <Card>
            <CardHeader>
              <CardTitle>{t("decisionTitle")}</CardTitle>
              <CardDescription>{t("decisionDescription")}</CardDescription>
            </CardHeader>
            <CardContent>
              <DecisionLogTab />
            </CardContent>
          </Card>
        </TabsContent>
      ) : null}
    </Tabs>
  );
}

function DatePickerField({
  id,
  label,
  value,
  onChange,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <div className="grid min-w-0 gap-2">
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        type="date"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="w-full min-w-0"
      />
    </div>
  );
}

function AuditLogSkeleton() {
  return (
    <div className="space-y-2">
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-10 w-full" />
    </div>
  );
}

function AuditLogState({
  title,
  description,
  action,
}: {
  title: string;
  description: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 rounded-lg border border-dashed p-8 text-center">
      <TriangleAlertIcon className="size-8 text-muted-foreground" />
      <div>
        <h2 className="font-medium">{title}</h2>
        <p className="text-muted-foreground text-sm">{description}</p>
      </div>
      {action}
    </div>
  );
}

function permittedPlants(plants: PlantView[], scope: ReturnType<typeof usePlantScope>["scope"]) {
  if (!scope || scope.mode === "EMPTY") {
    return [];
  }
  if (scope.mode === "UNRESTRICTED") {
    return plants;
  }
  const assignedIds = new Set((scope.availablePlants ?? []).map((plant) => plant.id));
  return plants.filter((plant) => plant.id && assignedIds.has(plant.id));
}
