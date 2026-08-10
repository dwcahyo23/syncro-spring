"use client";

import { useEffect, useState } from "react";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { StatusBadge } from "@/components/syncro/status-badge";
import { EmptyState } from "@/components/ui/empty-state";

export interface AlertsTabProps {
  machineCode: string;
}

interface CachedAlertsData {
  alerts?: AlertView[];
  loading?: boolean;
}

const cache = new Map<string, CachedAlertsData>();

export function AlertsTab({ machineCode }: AlertsTabProps) {
  const [data, setData] = useState<CachedAlertsData | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const fetchAlerts = async () => {
    try {
      setIsLoading(true);
      // This is a placeholder - implement actual alert fetching
      const response = await fetch(
        `/api/v1/machines/${encodeURIComponent(machineCode)}/alerts`
      );
      
      if (!response.ok) {
        throw new Error(`Failed to fetch alerts: ${response.statusText}`);
      }
      
      const result = await response.json();

      const cachedData: CachedAlertsData = {
        alerts: result.items || [],
      };

      cache.set(machineCode, cachedData);
      setData(cachedData);
    } catch (error) {
      console.error("Error fetching alerts:", error);
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
      fetchAlerts();
    }
  }, [machineCode]);

  if (isLoading) {
    return (
      <div className="flex min-h-[200px] items-center justify-center">
        <p className="text-muted-foreground">Loading alerts...</p>
      </div>
    );
  }

  if (!data?.alerts || data.alerts.length === 0) {
    return (
      <EmptyState
        title="No alerts"
        description="This machine has no active or recent alerts."
      />
    );
  }

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle>Machine Alerts</CardTitle>
          <CardDescription>Active and recent alerts for this machine</CardDescription>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>ID</TableHead>
                <TableHead>Type</TableHead>
                <TableHead>Severity</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Message</TableHead>
                <TableHead>Created At</TableHead>
                <TableHead>Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.alerts.map((alert) => (
                <TableRow key={alert.id}>
                  <TableCell className="font-mono text-sm">{alert.id}</TableCell>
                  <TableCell>{alert.alertType || "-"}</TableCell>
                  <TableCell>
                    <Badge variant={getSeverityVariant(alert.severity)}>
                      {formatSeverity(alert.severity)}
                    </Badge>
                  </TableCell>
                  <TableCell>
                    <StatusBadge
                      status={alert.isActive ? "ONLINE" : "OFFLINE"}
                      variant={alert.isActive ? "default" : "secondary"}
                    />
                  </TableCell>
                  <TableCell className="max-w-md truncate">{alert.message || "-"}</TableCell>
                  <TableCell>
                    {alert.createdAt ? formatDate(alert.createdAt) : "-"}
                  </TableCell>
                  <TableCell>
                    <button
                      onClick={() => viewAlertDetails(alert.id)}
                      className="text-primary hover:underline"
                    >
                      View
                    </button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Summary</CardTitle>
          <CardDescription>Alert statistics for this machine</CardDescription>
        </CardHeader>
        <CardContent>
          <dl className="grid gap-4 sm:grid-cols-3">
            <div>
              <dt className="text-sm font-medium text-muted-foreground">Total Alerts</dt>
              <dd className="mt-1 text-2xl font-bold">{data.alerts.length}</dd>
            </div>
            <div>
              <dt className="text-sm font-medium text-muted-foreground">Active Alerts</dt>
              <dd className="mt-1 text-2xl font-bold">
                {data.alerts.filter((a) => a.isActive).length}
              </dd>
            </div>
            <div>
              <dt className="text-sm font-medium text-muted-foreground">Critical Alerts</dt>
              <dd className="mt-1 text-2xl font-bold">
                {data.alerts.filter((a) => a.severity === "CRITICAL").length}
              </dd>
            </div>
          </dl>
        </CardContent>
      </Card>
    </div>
  );
}

interface AlertView {
  id: string;
  machineCode: string;
  alertType?: string;
  severity?: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
  status?: "ACTIVE" | "RESOLVED" | "ACKNOWLEDGED";
  isActive?: boolean;
  message?: string;
  createdAt?: string;
}

function formatDate(dateString: string): string {
  const date = new Date(dateString);
  return date.toLocaleString("en-US", {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function getSeverityVariant(severity?: string): "default" | "destructive" | "warning" | "secondary" {
  switch (severity) {
    case "CRITICAL":
      return "destructive";
    case "HIGH":
      return "destructive";
    case "MEDIUM":
      return "warning";
    case "LOW":
      return "secondary";
    default:
      return "default";
  }
}

function formatSeverity(severity?: string): string {
  if (!severity) return "Unknown";
  return severity.toLowerCase().replace(/\b\w/g, (l) => l.toUpperCase());
}

function viewAlertDetails(alertId: string) {
  console.log("Viewing alert details:", alertId);
  // TODO: Implement navigation to alert detail page or modal
}
