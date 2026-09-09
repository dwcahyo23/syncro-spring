"use client";

import { useState } from "react";

import { useFormatter, useTranslations } from "next-intl";

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
  const t = useTranslations("machineHub.audit");
  const tc = useTranslations("common");
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
        <EmptyState title={t("unavailableTitle")} description={t("unavailableDescription")} />
        <Button variant="outline" onClick={() => void refetch()}>
          {tc("retry")}
        </Button>
      </div>
    );
  }

  if (entries.length === 0) {
    return <EmptyState title={t("emptyTitle")} description={t("emptyDescription")} />;
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("title")}</CardTitle>
        <CardDescription>{t("description")}</CardDescription>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("timestamp")}</TableHead>
              <TableHead>{t("actor")}</TableHead>
              <TableHead>{t("action")}</TableHead>
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
  const t = useTranslations("machineHub.audit");
  const format = useFormatter();
  const hasChanges = entry.previousValue ?? entry.newValue;
  return (
    <>
      <TableRow>
        <TableCell>
          {entry.createdAt
            ? format.dateTime(new Date(entry.createdAt), { dateStyle: "medium", timeStyle: "short" })
            : "-"}
        </TableCell>
        <TableCell>{entry.actorName ?? "-"}</TableCell>
        <TableCell>
          <Badge variant="secondary">{entry.action}</Badge>
        </TableCell>
        <TableCell>
          {hasChanges && (
            <Button variant="ghost" size="sm" aria-label={t("toggleChangeAria")} onClick={onToggle}>
              {expanded ? t("hide") : t("show")}
            </Button>
          )}
        </TableCell>
      </TableRow>
      {expanded && (
        <TableRow>
          <TableCell colSpan={4} className="bg-muted/50">
            <dl className="grid gap-x-8 gap-y-2 text-xs md:grid-cols-2">
              <div>
                <dt className="font-medium">{t("before")}</dt>
                <dd className="mt-1 whitespace-pre-wrap">
                  {entry.previousValue ? JSON.stringify(entry.previousValue, null, 2) : "-"}
                </dd>
              </div>
              <div>
                <dt className="font-medium">{t("after")}</dt>
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
