"use client";

import { useState } from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { AuditLogEntryView } from "@/lib/api/generated/model";
import { useListAuditLogEntries } from "@/lib/api/generated/syncro";

export interface AuditLogTabProps {
  machineId?: string;
}

export function AuditLogTab({ machineId }: AuditLogTabProps) {
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const { data, isLoading, isError, refetch } = useListAuditLogEntries(
    { entityType: "MACHINE", entityId: machineId, size: 100, sort: "createdAt,desc" },
    { query: { enabled: Boolean(machineId), staleTime: 30_000 } },
  );

  const entries = data?.data?.items ?? [];

  if (!machineId || isLoading) {
    return (
      <Card>
        <CardContent className="space-y-4 py-6">
          {[0, 1, 2, 3, 4].map((i) => (
            <Skeleton key={i} className="h-10 w-full" />
          ))}
        </CardContent>
      </Card>
    );
  }

  if (isError) {
    return (
      <div className="flex flex-col items-start gap-3">
        <EmptyState title="Audit log unavailable" description="Failed to load audit log entries." />
        <Button variant="outline" onClick={() => void refetch()}>
          Retry
        </Button>
      </div>
    );
  }

  if (entries.length === 0) {
    return <EmptyState title="No audit entries yet" description="There are no audit log entries for this machine." />;
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Audit Log</CardTitle>
        <CardDescription>Immutable history of changes to this machine</CardDescription>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Timestamp</TableHead>
              <TableHead>Actor</TableHead>
              <TableHead>Action</TableHead>
              <TableHead className="w-12" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {entries.map((entry) => (
              <AuditLogRow
                key={entry.id}
                entry={entry}
                expanded={expandedId === entry.id}
                onToggle={() => setExpandedId(expandedId === entry.id ? null : (entry.id ?? null))}
              />
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}

function AuditLogRow({
  entry,
  expanded,
  onToggle,
}: {
  entry: AuditLogEntryView;
  expanded: boolean;
  onToggle: () => void;
}) {
  const hasChanges = entry.previousValue || entry.newValue;
  return (
    <>
      <TableRow>
        <TableCell>{entry.createdAt ? new Date(entry.createdAt).toLocaleString() : "-"}</TableCell>
        <TableCell>{entry.actorName ?? "-"}</TableCell>
        <TableCell>
          <Badge variant="secondary">{entry.action}</Badge>
        </TableCell>
        <TableCell>
          {hasChanges && (
            <Button variant="ghost" size="sm" aria-label="Toggle change detail" onClick={onToggle}>
              {expanded ? "Hide" : "Show"}
            </Button>
          )}
        </TableCell>
      </TableRow>
      {expanded && (
        <TableRow>
          <TableCell colSpan={4} className="bg-muted/50">
            <dl className="grid gap-x-8 gap-y-2 text-xs md:grid-cols-2">
              <div>
                <dt className="font-medium">Before</dt>
                <dd className="mt-1 whitespace-pre-wrap">
                  {entry.previousValue ? JSON.stringify(entry.previousValue, null, 2) : "-"}
                </dd>
              </div>
              <div>
                <dt className="font-medium">After</dt>
                <dd className="mt-1 whitespace-pre-wrap">
                  {entry.newValue ? JSON.stringify(entry.newValue, null, 2) : "-"}
                </dd>
              </div>
            </dl>
          </TableCell>
        </TableRow>
      )}
    </>
  );
}
