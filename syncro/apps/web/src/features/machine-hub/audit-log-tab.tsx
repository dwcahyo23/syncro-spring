"use client";

import { useEffect, useState } from "react";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { EmptyState } from "@/components/ui/empty-state";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";

export interface AuditLogTabProps {
  machineCode: string;
}

interface CachedAuditLogData {
  entries?: AuditLogEntry[];
  loading?: boolean;
}

const cache = new Map<string, CachedAuditLogData>();

export function AuditLogTab({ machineCode }: AuditLogTabProps) {
  const [data, setData] = useState<CachedAuditLogData | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const fetchAuditLog = async () => {
    try {
      setIsLoading(true);
      const response = await fetch(
        `/api/v1/machines/${encodeURIComponent(machineCode)}/audit-log?pageSize=50`
      );
      
      if (!response.ok) {
        throw new Error(`Failed to fetch audit log: ${response.statusText}`);
      }
      
      const result = await response.json();

      const cachedData: CachedAuditLogData = {
        entries: result.items || [],
      };

      cache.set(machineCode, cachedData);
      setData(cachedData);
    } catch (error) {
      console.error("Error fetching audit log:", error);
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
      fetchAuditLog();
    }
  }, [machineCode]);

  if (isLoading) {
    return (
      <Card>
        <CardContent className="space-y-4 py-6">
          {[...Array(5)].map((_, i) => (
            <div key={i} className="flex items-center gap-4">
              <Skeleton className="h-10 w-full" />
            </div>
          ))}
        </CardContent>
      </Card>
    );
  }

  if (!data?.entries || data.entries.length === 0) {
    return (
      <EmptyState
        title="No audit log entries"
        description="There are no audit log entries for this machine."
      />
    );
  }

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle>Audit Log</CardTitle>
          <CardDescription>
            Historical record of all actions and changes related to this machine
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>ID</TableHead>
                <TableHead>Action</TableHead>
                <TableHead>User</TableHead>
                <TableHead>Timestamp</TableHead>
                <TableHead>Details</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.entries.map((entry) => (
                <TableRow key={entry.id}>
                  <TableCell className="font-mono text-xs">{entry.id}</TableCell>
                  <TableCell>
                    <Badge variant="secondary">{formatAction(entry.action)}</Badge>
                  </TableCell>
                  <TableCell>{entry.userEmail || entry.userName || "-"}</TableCell>
                  <TableCell>
                    {entry.createdAt ? formatDate(entry.createdAt) : "-"}
                  </TableCell>
                  <TableCell className="max-w-md truncate">
                    {truncateText(entry.details, 100)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {data.entries.length > 10 && (
        <div className="text-center text-sm text-muted-foreground">
          Showing last {data.entries.length} of {data.entries.length} entries
        </div>
      )}
    </div>
  );
}

interface AuditLogEntry {
  id: string;
  machineCode: string;
  action: string;
  userName?: string;
  userEmail?: string;
  timestamp?: string;
  createdAt?: string;
  details?: string;
}

function formatDate(dateString?: string): string {
  if (!dateString) return "-";
  const date = new Date(dateString);
  return date.toLocaleString("en-US", {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

function formatAction(action: string): string {
  return action
    .toLowerCase()
    .split("-")
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}

function truncateText(text: string | undefined, maxLength: number): string {
  if (!text) return "-";
  if (text.length <= maxLength) return text;
  return `${text.substring(0, maxLength)}...`;
}
