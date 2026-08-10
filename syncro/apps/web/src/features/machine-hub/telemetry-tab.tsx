"use client";

import { AlertTriangle } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { StatusBadge } from "@/components/syncro/status-badge";
import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { useGetMachineByCode } from "@/lib/api/generated/syncro";

export interface TelemetryTabProps {
  machineCode: string;
}

const POLL_INTERVAL_MS = 30_000;

export function TelemetryTab({ machineCode }: TelemetryTabProps) {
  const { data, isLoading, isError, refetch } = useGetMachineByCode(machineCode, {
    query: { refetchInterval: POLL_INTERVAL_MS, staleTime: POLL_INTERVAL_MS / 2 },
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
      <EmptyState
        title="Telemetry unavailable"
        description="Failed to load telemetry data. Use refresh to retry."
      />
    );
  }

  if (machine?.status === "INACTIVE") {
    return (
      <div className="flex items-start gap-3 rounded-lg border border-amber-500/40 bg-amber-500/10 p-4">
        <AlertTriangle className="size-5 shrink-0 text-amber-600" aria-hidden="true" />
        <p className="text-sm">
          This machine is currently INACTIVE. Telemetry messages are being rejected.
        </p>
      </div>
    );
  }

  if (!telemetry) {
    return (
      <EmptyState
        title="No telemetry available"
        description="This machine has not sent any telemetry data yet."
      />
    );
  }

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
        <div>
          <CardTitle>Live Telemetry</CardTitle>
          <CardDescription>Latest sensor readings (auto-refreshes every 30 seconds)</CardDescription>
        </div>
        <div className="flex items-center gap-4">
          {telemetry.freshnessState && <StatusBadge freshness={telemetry.freshnessState} />}
          <button
            type="button"
            onClick={() => void refetch()}
            className="rounded-md bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/90"
          >
            Refresh
          </button>
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
              <TableCell>{telemetry.running ? "Yes" : "No"}</TableCell>
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
                <TableCell>
                  <Badge variant="secondary">{String(value)}</Badge>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {telemetry.receivedAt && (
          <p className="mt-4 text-sm text-muted-foreground">
            Last received: {new Date(telemetry.receivedAt).toLocaleString()}
          </p>
        )}
      </CardContent>
    </Card>
  );
}
