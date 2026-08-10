"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import type { MachineView, SyncroApiError } from "@/lib/api/generated";
import { getMachineByCode } from "@/lib/api/generated/syncro";
import { StatusBadge } from "@/components/syncro/status-badge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { toast } from "sonner";
import { MachineHeader } from "./machine-header";
import { OverviewTab } from "./overview-tab";
import { TelemetryTab } from "./telemetry-tab";
import { SparepartsTab } from "./spareparts-tab";
import { AlertsTab } from "./alerts-tab";
import { AuditLogTab } from "./audit-log-tab";

export function MachineHubPageContent() {
  const params = useParams();
  const router = useRouter();
  const machineCode = params.machineCode as string;

  const [activeTab, setActiveTab] = useState("overview");

  const { data: machine, isLoading: isFetchingMachine, refetch } = useGetMachineByCode({
    queryKey: ["machine", machineCode],
    queryFn: ({ signal }) => getMachineByCode(machineCode, { signal }),
    onError: (error) => {
      if (error instanceof SyncroApiError && error.status === 403) {
        toast.error("Access denied: You have no plant assignment for this machine.");
      } else if (error instanceof SyncroApiError && error.status === 404) {
        router.replace("/dashboard/operations-overview");
      } else {
        toast.error("Failed to load machine data");
      }
    },
  });

  const handleRefresh = () => {
    refetch().catch(() => toast.error("Refresh failed"));
  };

  const handleTabChange = (value: string) => {
    setActiveTab(value);
  };

  return (
    <div className="flex flex-col gap-6">
      <MachineHeader
        machineCode={machineCode}
        onRefresh={handleRefresh}
        isLoading={isFetchingMachine}
        machineStatus={machine?.status}
        freshnessState={machine?.latestTelemetry?.freshnessState}
      />

      <Tabs value={activeTab} onValueChange={handleTabChange} className="flex-1">
        <TabsList className="w-full justify-start overflow-x-auto">
          <TabsTrigger value="overview">Overview</TabsTrigger>
          <TabsTrigger value="telemetry">Telemetry</TabsTrigger>
          <TabsTrigger value="spareparts">Spareparts</TabsTrigger>
          <TabsTrigger value="alerts">Alerts</TabsTrigger>
          <TabsTrigger value="audit">Audit Log</TabsTrigger>
        </TabsList>

        <TabsContent value="overview" className="mt-6">
          <OverviewTab machine={machine} />
        </TabsContent>

        <TabsContent value="telemetry" className="mt-6">
          <TelemetryTab machineCode={machineCode} machineId={machine?.id || ""} />
        </TabsContent>

        <TabsContent value="spareparts" className="mt-6">
          <SparepartsTab machineId={machine?.id || ""} />
        </TabsContent>

        <TabsContent value="alerts" className="mt-6">
          <AlertsTab machineCode={machineCode} />
        </TabsContent>

        <TabsContent value="audit" className="mt-6">
          <AuditLogTab machineId={machine?.id || ""} />
        </TabsContent>
      </Tabs>
    </div>
  );
}
