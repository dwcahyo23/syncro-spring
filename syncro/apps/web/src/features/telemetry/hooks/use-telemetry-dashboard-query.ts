import { useMemo } from "react";

import type { ListMachinesParams } from "@/lib/api/generated/model";
import { useListMachines } from "@/lib/api/generated/syncro";
import type { TelemetryMachineView } from "@/features/telemetry/types";

export const TELEMETRY_REFRESH_INTERVAL_MS = 30_000;

export function useTelemetryDashboardQuery(plantId: string | undefined, enabled = true) {
  const params = useMemo<ListMachinesParams>(
    () => ({
      status: "ACTIVE",
      plantId,
      size: 200,
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
