"use client";

import { useState } from "react";

import { AlertTriangle } from "lucide-react";
import { toast } from "sonner";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import type { getMachineByCodeResponse } from "@/lib/api/generated/syncro";
import { useGetMachineByCode } from "@/lib/api/generated/syncro";
import { SyncroApiError } from "@/lib/api/orval-mutator";

import { AlertsTab } from "./alerts-tab";
import { AuditLogTab } from "./audit-log-tab";
import { MachineHeader } from "./machine-header";
import { OverviewTab } from "./overview-tab";
import { SparepartsTab } from "./spareparts-tab";
import { TelemetryTab } from "./telemetry-tab";

export function MachineHubPageContent({ machineCode }: { machineCode: string }) {
  const [activeTab, setActiveTab] = useState("overview");

  const machineQuery = useGetMachineByCode<getMachineByCodeResponse, SyncroApiError>(machineCode, {
    query: {
      retry: (failureCount, error) =>
        failureCount < 2 && !(error instanceof SyncroApiError && (error.status === 403 || error.status === 404)),
      staleTime: 15_000,
    },
  });

  const machine = machineQuery.data?.data;
  const error = machineQuery.error;

  if (machineQuery.isError) {
    if (error instanceof SyncroApiError && error.status === 404) {
      return (
        <div className="rounded-lg border p-6 text-center">
          <h2 className="text-lg font-semibold">Machine not found</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            No machine with code &quot;{machineCode}&quot; exists or is visible to you.
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
        <div className="flex items-start gap-3 rounded-lg border border-amber-500/40 bg-amber-500/10 p-4">
          <AlertTriangle className="size-5 shrink-0 text-amber-600" aria-hidden="true" />
          <p className="text-sm">This machine is currently INACTIVE. Telemetry messages are being rejected.</p>
        </div>
      )}

      <MachineHeader
        machineCode={machineCode}
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

        <TabsContent value="overview" className="mt-6">
          <OverviewTab machine={machine} isLoading={machineQuery.isLoading} />
        </TabsContent>

        <TabsContent value="telemetry" className="mt-6">
          <TelemetryTab machineCode={machineCode} isActive={activeTab === "telemetry"} />
        </TabsContent>

        <TabsContent value="spareparts" className="mt-6">
          <SparepartsTab machineId={machine?.id} />
        </TabsContent>

        <TabsContent value="alerts" className="mt-6">
          <AlertsTab machineCode={machineCode} />
        </TabsContent>

        <TabsContent value="audit" className="mt-6">
          <AuditLogTab machineId={machine?.id} />
        </TabsContent>
      </Tabs>
    </div>
  );
}
