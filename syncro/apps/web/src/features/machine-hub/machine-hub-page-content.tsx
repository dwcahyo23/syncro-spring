"use client";

import { useState } from "react";

import { AlertTriangle } from "lucide-react";
import { toast } from "sonner";

import { CounterRateProjectionCard } from "@/components/syncro/counter-rate-projection-card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import type { getMachineByCodeResponse, getMachineResponse } from "@/lib/api/generated/syncro";
import { useGetMachine, useGetMachineByCode } from "@/lib/api/generated/syncro";
import { SyncroApiError } from "@/lib/api/orval-mutator";

import { AlertsTab } from "./alerts-tab";
import { AuditLogTab } from "./audit-log-tab";
import { MachineHeader } from "./machine-header";
import { OverviewTab } from "./overview-tab";
import { ShiftSection } from "./shift-section";
import { SparepartsTab } from "./spareparts-tab";
import { TelemetryTab } from "./telemetry-tab";

/**
 * Machine hub (DW-71): resolves by {@code machineId} when provided (unambiguous across
 * plants) and falls back to code-based resolution otherwise. Code-only resolution is
 * ambiguous when the same code exists in two plants; links that carry a machineId
 * (e.g. the system-health stale-machine list) must use the ID path.
 */
export function MachineHubPageContent({ machineCode, machineId }: { machineCode?: string; machineId?: string }) {
  const [activeTab, setActiveTab] = useState("overview");

  const machineCodeQuery = useGetMachineByCode<getMachineByCodeResponse, SyncroApiError>(machineCode ?? "", {
    query: {
      enabled: machineId == null && machineCode != null,
      retry: (failureCount, error) =>
        failureCount < 2 && !(error instanceof SyncroApiError && (error.status === 403 || error.status === 404)),
      staleTime: 15_000,
    },
  });

  const machineIdQuery = useGetMachine<getMachineResponse, SyncroApiError>(machineId ?? "", {
    query: {
      enabled: machineId != null,
      retry: (failureCount, error) =>
        failureCount < 2 && !(error instanceof SyncroApiError && (error.status === 403 || error.status === 404)),
      staleTime: 15_000,
    },
  });

  const machineQuery = machineId != null ? machineIdQuery : machineCodeQuery;
  const resolvedCode = machineQuery.data?.data?.code ?? machineCode ?? "";

  const machine = machineQuery.data?.data;
  const error = machineQuery.error;

  if (machineQuery.isError) {
    if (error instanceof SyncroApiError && error.status === 404) {
      return (
        <div className="rounded-lg border p-6 text-center">
          <h2 className="text-lg font-semibold">Machine not found</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            No machine with code &quot;{resolvedCode}&quot; exists or is visible to you.
          </p>
        </div>
      );
    }
    if (error instanceof SyncroApiError && error.status === 403) {
      return (
        <div className="rounded-lg border p-6 text-center">
          <h2 className="text-lg font-semibold">Access denied</h2>
          <p className="mt-1 text-sm text-muted-foreground">You have no plant assignment for this machine.</p>
        </div>
      );
    }
    return (
      <div className="flex flex-col items-center gap-3 rounded-lg border p-6">
        <p className="text-sm text-muted-foreground">Failed to load machine data.</p>
        <button
          type="button"
          className="rounded-md bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/90"
          onClick={() => void machineQuery.refetch()}
        >
          Retry
        </button>
      </div>
    );
  }

  const handleRefresh = () => {
    void machineQuery.refetch().catch(() => toast.error("Refresh failed"));
  };

  return (
    <div className="flex flex-col gap-6">
      {machine?.status === "INACTIVE" && (
        <div className="status-banner-warning flex items-start gap-3 rounded-lg border p-4">
          <AlertTriangle className="status-icon-warning size-5 shrink-0" aria-hidden="true" />
          <p className="text-sm">This machine is currently INACTIVE. Telemetry messages are being rejected.</p>
        </div>
      )}

      <MachineHeader
        machineCode={resolvedCode}
        machine={machine}
        freshnessState={machine?.latestTelemetry?.freshnessState}
        isLoading={machineQuery.isFetching}
        onRefresh={handleRefresh}
      />

      <Tabs value={activeTab} onValueChange={setActiveTab} className="flex-1">
        <TabsList className="w-full justify-start overflow-x-auto">
          <TabsTrigger value="overview">Overview</TabsTrigger>
          <TabsTrigger value="telemetry">Telemetry</TabsTrigger>
          <TabsTrigger value="spareparts">Spareparts</TabsTrigger>
          <TabsTrigger value="alerts">Alerts</TabsTrigger>
          <TabsTrigger value="audit">Audit Log</TabsTrigger>
        </TabsList>

        <TabsContent value="overview" className="mt-6 space-y-6">
          <OverviewTab machine={machine} isLoading={machineQuery.isLoading} />
          {machine?.id ? <ShiftSection machineId={machine.id} /> : null}
          {machine?.id ? <CounterRateProjectionCard machineId={machine.id} /> : null}
        </TabsContent>

        <TabsContent value="telemetry" className="mt-6">
          <TelemetryTab machineCode={resolvedCode} isActive={activeTab === "telemetry"} />
        </TabsContent>

        <TabsContent value="spareparts" className="mt-6">
          <SparepartsTab machineId={machine?.id} />
        </TabsContent>

        <TabsContent value="alerts" className="mt-6">
          <AlertsTab machineId={machine?.id} />
        </TabsContent>

        <TabsContent value="audit" className="mt-6">
          <AuditLogTab machineId={machine?.id} />
        </TabsContent>
      </Tabs>
    </div>
  );
}
