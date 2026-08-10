"use client";

import { useEffect, useMemo, useState } from "react";
import { useParams, useRouter } from "next/navigation";

import { StatusBadge } from "@/components/syncro/status-badge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import type { LatestTelemetryDto } from "@/lib/api/generated/model";
import { SyncroApiError } from "@/lib/api/orval-mutator";
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
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    setIsLoading(true);
    fetchMachineData(machineCode).catch((error) => {
      if (error instanceof SyncroApiError && error.status === 403) {
        toast.error("Access denied: You have no plant assignment for this machine.");
      } else {
        toast.error("Failed to load machine data");
      }
    }).finally(() => {
      setIsLoading(false);
    });
  }, [machineCode]);

  const handleRefresh = () => {
    setIsLoading(true);
    fetchMachineData(machineCode).finally(() => setIsLoading(false));
  };

  const handleTabChange = (value: string) => {
    setActiveTab(value);
  };

  return (
    <div className="flex flex-col gap-6">
      <MachineHeader
        machineCode={machineCode}
        onRefresh={handleRefresh}
        isLoading={isLoading}
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
          <OverviewTab machineCode={machineCode} />
        </TabsContent>

        <TabsContent value="telemetry" className="mt-6">
          <TelemetryTab machineCode={machineCode} />
        </TabsContent>

        <TabsContent value="spareparts" className="mt-6">
          <SparepartsTab machineCode={machineCode} />
        </TabsContent>

        <TabsContent value="alerts" className="mt-6">
          <AlertsTab machineCode={machineCode} />
        </TabsContent>

        <TabsContent value="audit" className="mt-6">
          <AuditLogTab machineCode={machineCode} />
        </TabsContent>
      </Tabs>
    </div>
  );
}

async function fetchMachineData(machineCode: string) {
  await Promise.all([
    fetch(`/api/v1/machines/code/${encodeURIComponent(machineCode)}`),
    fetch(`/api/v1/telemetry/latest?machineCode=${encodeURIComponent(machineCode)}`),
  ]);
}
