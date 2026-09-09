import { useMemo } from "react";

import type { TelemetryMachineView } from "@/features/telemetry/types";
import type { ListMachinesParams } from "@/lib/api/generated/model";
import { useListMachines } from "@/lib/api/generated/syncro";

export const TELEMETRY_REFRESH_INTERVAL_MS = 30_000;

// Hard page cap requested from /machines; fleets larger than this are truncated server-side.
export const TELEMETRY_DASHBOARD_MAX_MACHINES = 200;

export function useTelemetryDashboardQuery(plantId: string | undefined, enabled = true) {
  const params = useMemo<ListMachinesParams>(
    () => ({
      status: "ACTIVE",
      plantId,
      size: TELEMETRY_DASHBOARD_MAX_MACHINES,
      sort: "code,asc",
    }),
    [plantId],
  );

  const query = useListMachines(params, {
    query: {
      enabled,
      refetchInterval: TELEMETRY_REFRESH_INTERVAL_MS,
    },
  });

  const machines = (query.data?.data?.items ?? []) as TelemetryMachineView[];

  return { ...query, machines };
}
