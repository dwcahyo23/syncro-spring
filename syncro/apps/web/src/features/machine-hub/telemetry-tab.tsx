"use client";

import { useEffect, useMemo, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { StatusBadge } from "@/components/syncro/status-badge";
import { EmptyState } from "@/components/ui/empty-state";

export interface TelemetryTabProps {
  machineCode: string;
}

interface TelemetryData {
  timestamp?: string;
  data?: Record<string, unknown>;
  isOnline?: boolean;
}

const cache = new Map<string, TelemetryData>();

export function TelemetryTab({ machineCode }: TelemetryTabProps) {
  const [data, setData] = useState<TelemetryData | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [refreshKey, setRefreshKey] = useState(0);

  const doRefresh = () => {
    setRefreshKey((k) => k + 1);
  };

  const fetchTelemetry = async () => {
    try {
      const response = await fetch(`/api/v1/telemetry/latest?machineCode=${encodeURIComponent(machineCode)}`);
      if (!response.ok) {
        throw new Error(`Failed to fetch telemetry: ${response.statusText}`);
      }
      const telemetry = await response.json();

      // Calculate freshness
      const fiveMinutesAgo = Date.now() - 5 * 60 * 1000;
      const telemetryTime = new Date(telemetry.timestamp || "").getTime();
      const isOnline = telemetryTime > fiveMinutesAgo && telemetryTime > 0;

      const cachedData: TelemetryData = {
        ...telemetry,
        isOnline,
      };

      cache.set(machineCode, cachedData);
      setData(cachedData);
    } catch (error) {
      console.error("Error fetching telemetry:", error);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    const cached = cache.get(machineCode);
    if (cached) {
      setData(cached);
      setIsLoading(false);
    } else {
      fetchTelemetry();
    }
  }, [machineCode, refreshKey]);

  useEffect(() => {
    if (!data?.timestamp) return;

    const updateFreshness = () => {
      const cached = cache.get(machineCode);
      if (!cached?.timestamp) return;

      const fiveMinutesAgo = Date.now() - 5 * 60 * 1000;
      const telemetryTime = new Date(cached.timestamp).getTime();
      const isOnline = telemetryTime > fiveMinutesAgo;

      if (cached.isOnline !== isOnline) {
        const updatedCache = { ...cached, isOnline };
        cache.set(machineCode, updatedCache);
        setData(updatedCache);
      }
    };

    const interval = setInterval(updateFreshness, 10_000);
    return () => clearInterval(interval);
  }, [machineCode, data?.timestamp]);

  if (isLoading) {
    return (
      <div className="flex min-h-[200px] items-center justify-center">
        <p className="text-muted-foreground">Loading telemetry...</p>
      </div>
    );
  }

  if (!data || !data.data || Object.keys(data.data).length === 0) {
    return (
      <EmptyState
        title="No telemetry available"
        description="This machine has not sent any telemetry data yet."
      />
    );
  }

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
          <div>
            <CardTitle>Live Telemetry</CardTitle>
            <CardDescription>Real-time sensor readings and status</CardDescription>
          </div>
          <div className="flex items-center gap-4">
            {data.isOnline !== undefined && (
              <StatusBadge
                status={data.isOnline ? "ONLINE" : "STALE"}
                variant={data.isOnline ? "success" : "warning"}
              />
            )}
            <button
              onClick={doRefresh}
              disabled={isLoading}
              className="rounded-md bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
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
                <TableHead>Type</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {Object.entries(data.data).map(([key, value]) => (
                <TableRow key={key}>
                  <TableCell className="font-medium">{key}</TableCell>
                  <TableCell>{formatValue(value)}</TableCell>
                  <TableCell>
                    <Badge variant="secondary">{getType(value)}</Badge>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {data.timestamp && (
            <p className="mt-4 text-sm text-muted-foreground">
              Last received: {new Date(data.timestamp).toLocaleString()}{" "}
              {data.isOnline === false && "(Stale - no data for more than 5 minutes)"}
            </p>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function formatValue(value: unknown): string {
  if (value === null || value === undefined) {
    return "-";
  }
  if (typeof value === "object") {
    return JSON.stringify(value);
  }
  return String(value);
}

function getType(value: unknown): string {
  if (value === null) return "null";
  if (value === undefined) return "undefined";
  return typeof value;
}
