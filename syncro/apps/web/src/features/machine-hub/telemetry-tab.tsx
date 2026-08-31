"use client";

import { AlertTriangle } from "lucide-react";

import { StatusBadge } from "@/components/syncro/status-badge";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useGetMachineByCode } from "@/lib/api/generated/syncro";

export interface TelemetryTabProps {
  machineCode: string;
  isActive?: boolean;
}

const POLL_INTERVAL_MS = 30_000;

export function TelemetryTab({ machineCode, isActive = true }: TelemetryTabProps) {
  const { data, isLoading, isError, refetch } = useGetMachineByCode(machineCode, {
    query: {
      enabled: isActive,
      refetchInterval: isActive ? POLL_INTERVAL_MS : false,
      staleTime: POLL_INTERVAL_MS / 2,
    },
  });

  const machine = data?.data;
  const telemetry = machine?.latestTelemetry;

  if (isLoading) {
    return (
      <div className="space-y-3">
        <Skeleton className="h-40 w-full" />
        <Skeleton className="h-10 w-2/3" />
      </div>
    );
  }

  if (isError) {
    return (
      <div className="flex flex-col items-start gap-3">
        <EmptyState title="Telemetry unavailable" description="Failed to load telemetry data." />
        <Button variant="outline" onClick={() => void refetch()}>
          Retry
        </Button>
      </div>
    );
  }

  if (machine?.status === "INACTIVE") {
    return (
      <div className="status-banner-warning flex items-start gap-3 rounded-lg border p-4">
        <AlertTriangle className="status-icon-warning size-5 shrink-0" aria-hidden="true" />
        <p className="text-sm">This machine is currently INACTIVE. Telemetry messages are being rejected.</p>
      </div>
    );
  }

  if (!telemetry) {
    return (
      <EmptyState title="No telemetry available" description="This machine has not sent any telemetry data yet." />
    );
  }

  const receivedAt = telemetry.lastReceivedAt ? new Date(telemetry.lastReceivedAt) : null;

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
        <div>
          <CardTitle>Latest Telemetry</CardTitle>
          <CardDescription>Latest sensor readings (auto-refreshes every 30 seconds)</CardDescription>
        </div>
        <div className="flex items-center gap-4">
          {telemetry.freshnessState && <StatusBadge freshness={telemetry.freshnessState} />}
          <Button variant="outline" size="sm" onClick={() => void refetch()}>
            Refresh
          </Button>
        </div>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Field</TableHead>
              <TableHead>Value</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow>
              <TableCell className="font-medium">Running</TableCell>
              <TableCell>
                <Badge variant={telemetry.running ? "default" : "secondary"}>{telemetry.running ? "Yes" : "No"}</Badge>
              </TableCell>
            </TableRow>
            <TableRow>
              <TableCell className="font-medium">Runtime Hours</TableCell>
              <TableCell>{telemetry.runtimeHours ?? "-"}</TableCell>
            </TableRow>
            <TableRow>
              <TableCell className="font-medium">Production Count</TableCell>
              <TableCell>{telemetry.counting ?? "-"}</TableCell>
            </TableRow>
            {Object.entries(telemetry.optionalFields ?? {}).map(([key, value]) => (
              <TableRow key={key}>
                <TableCell className="font-medium">{key}</TableCell>
                <TableCell>{value}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {receivedAt && (
          <p className="mt-4 text-sm text-muted-foreground">Last received: {receivedAt.toLocaleString()}</p>
        )}
      </CardContent>
    </Card>
  );
}
