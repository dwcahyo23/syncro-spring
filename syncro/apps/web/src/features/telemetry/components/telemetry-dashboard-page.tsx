"use client";

import { useEffect, useMemo, useState } from "react";

import { RefreshCw, TriangleAlertIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Empty, EmptyContent, EmptyDescription, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { usePlantScope } from "@/features/plant-scope/plant-scope-store";
import { TelemetryGrid } from "@/features/telemetry/components/telemetry-grid";
import { useTelemetryDashboardQuery } from "@/features/telemetry/hooks/use-telemetry-dashboard-query";
import type { PlantView } from "@/lib/api/generated/model";
import { useListPlants } from "@/lib/api/generated/syncro";

const STALE_BANNER_THRESHOLD_MS = 60_000;

function useNow(intervalMs: number) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs]);
  return now;
}

export function TelemetryDashboardPage() {
  const plantScope = usePlantScope();
  const scope = plantScope.scope;
  const isAssignedEmpty = scope?.mode === "EMPTY";
  const plants = useListPlants({ query: { enabled: Boolean(scope) && !isAssignedEmpty } });
  const plantItems = plants.data?.data.items ?? [];
  const availablePlants = useMemo(() => permittedPlants(plantItems, scope), [plantItems, scope]);

  const [plantId, setPlantId] = useState<string>("ALL");
  const effectivePlantId = plantId === "ALL" ? undefined : plantId;

  const telemetry = useTelemetryDashboardQuery(isAssignedEmpty ? undefined : effectivePlantId, !isAssignedEmpty);
  const totalCount = telemetry.data?.data?.totalElements ?? telemetry.machines.length;

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="space-y-1">
          <h1 className="font-semibold text-2xl tracking-tight">Telemetry</h1>
          <p className="text-muted-foreground text-sm">
            Latest machine state refreshed automatically every 30 seconds.
          </p>
        </div>
        <Button variant="outline" onClick={() => void telemetry.refetch()} disabled={telemetry.isLoading}>
          <RefreshCw aria-hidden="true" className={telemetry.isFetching ? "animate-spin" : undefined} />
          Refresh
        </Button>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Latest telemetry</CardTitle>
          <CardDescription>
            Telemetry freshness is separate from manual machine status. ONLINE means data within 5 minutes; STALE
            means no data for more than 15 minutes.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-wrap items-end gap-3">
            <div className="grid min-w-0 gap-2">
              <Label htmlFor="telemetry-plant">Plant</Label>
              <Select value={plantId} onValueChange={setPlantId} disabled={isAssignedEmpty || plants.isLoading}>
                <SelectTrigger id="telemetry-plant" className="w-56 min-w-0">
                  <SelectValue placeholder="Plant" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="ALL">All plants</SelectItem>
                  {availablePlants.map((plant) => (
                    <SelectItem key={plant.id ?? plant.code} value={plant.id ?? ""}>
                      {plant.code} · {plant.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <p aria-live="polite" className="pb-2 text-muted-foreground text-xs">
              {telemetry.isLoading
                ? "Loading telemetry…"
                : `${telemetry.machines.length} active ${
                    telemetry.machines.length === 1 ? "machine" : "machines"
                  }${
                    totalCount > telemetry.machines.length
                      ? ` (showing first ${telemetry.machines.length} of ${totalCount} matching)`
                      : ""
                  }`}
            </p>
          </div>

          <TelemetryBody isAssignedEmpty={isAssignedEmpty} plantId={plantId} telemetry={telemetry} />
        </CardContent>
      </Card>
    </div>
  );
}

function TelemetrySkeleton() {
  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {["s1", "s2", "s3", "s4", "s5", "s6"].map((key) => (
        <Skeleton key={key} className="h-52 w-full" />
      ))}
    </div>
  );
}

function TelemetryBody({
  isAssignedEmpty,
  plantId,
  telemetry,
}: {
  isAssignedEmpty: boolean;
  plantId: string;
  telemetry: ReturnType<typeof useTelemetryDashboardQuery>;
}) {
  const now = useNow(30_000);
  const lastUpdated = telemetry.dataUpdatedAt;
  const isDataStale = lastUpdated > 0 && now - lastUpdated >= STALE_BANNER_THRESHOLD_MS;

  if (isAssignedEmpty) {
    return <TelemetryState title="No plants assigned" description="No plants assigned. Contact your administrator." />;
  }
  if (telemetry.isLoading) {
    return <TelemetrySkeleton />;
  }
  if (telemetry.isError) {
    return (
      <TelemetryState
        icon={<TriangleAlertIcon aria-hidden="true" className="text-destructive" />}
        title="Telemetry could not be loaded"
        description="The telemetry dashboard failed to load. Retry to fetch it again."
        action={
          <Button variant="outline" onClick={() => void telemetry.refetch()}>
            Retry
          </Button>
        }
      />
    );
  }
  if (telemetry.machines.length === 0) {
    return (
      <TelemetryState
        title="No active machines found"
        description={
          plantId === "ALL"
            ? "Activate machines to see their latest telemetry here."
            : "No ACTIVE machines in this plant yet."
        }
      />
    );
  }
  return (
    <div className="space-y-3">
      {isDataStale ? (
        <div
          aria-live="polite"
          className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2"
        >
          <p className="flex items-center gap-2 text-sm">
            <TriangleAlertIcon aria-hidden="true" className="text-amber-600 dark:text-amber-400" />
            Last updated {formatMinutesAgo(now - lastUpdated)} ago. Automatic refresh may be delayed.
          </p>
          <Button size="sm" variant="outline" onClick={() => void telemetry.refetch()}>
            Refresh now
          </Button>
        </div>
      ) : null}
      <TelemetryGrid machines={telemetry.machines} />
    </div>
  );
}

function formatMinutesAgo(ageMs: number) {
  const minutes = Math.max(1, Math.floor(ageMs / 60_000));
  return `${minutes} min`;
}

function TelemetryState({
  title,
  description,
  action,
  icon,
}: {
  title: string;
  description: string;
  action?: React.ReactNode;
  icon?: React.ReactNode;
}) {
  return (
    <Empty className="min-h-40">
      <EmptyMedia variant="icon">{icon ?? <TriangleAlertIcon aria-hidden="true" />}</EmptyMedia>
      <EmptyTitle>{title}</EmptyTitle>
      <EmptyDescription>{description}</EmptyDescription>
      {action ? <EmptyContent>{action}</EmptyContent> : null}
    </Empty>
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
