"use client";

import { useState } from "react";

import { TriangleAlertIcon } from "lucide-react";
import { useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTablePagination } from "@/components/ui/data-table-pagination";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { AuthzDecisionView } from "@/lib/api/generated/model";
import { useGetAuthzDecisions } from "@/lib/api/generated/syncro";
import { useDateTimeFormatter } from "@/lib/i18n/format";

export function DecisionLogTab() {
  const t = useTranslations("auditLog");
  const tc = useTranslations("common");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);

  const decisions = useGetAuthzDecisions({ page, size }, { query: { queryKey: ["authz-decisions", page, size] } });
  const items = decisions.data?.data.items ?? [];

  return (
    <div className="space-y-4">
      {decisions.isLoading ? <DecisionLogSkeleton /> : null}
      {decisions.isError ? (
        <DecisionLogState
          title={t("decision.loadError.title")}
          description={t("decision.loadError.description")}
          action={
            <Button variant="outline" onClick={() => void decisions.refetch()}>
              {tc("retry")}
            </Button>
          }
        />
      ) : null}
      {!decisions.isLoading && !decisions.isError && items.length === 0 ? (
        <DecisionLogState title={t("decision.empty.title")} description={t("decision.empty.description")} />
      ) : null}
      {!decisions.isLoading && !decisions.isError && items.length > 0 ? (
        <>
          <div className="hidden md:block">
            <DecisionLogTable decisions={items} />
          </div>
          <div className="md:hidden">
            <DecisionLogCards decisions={items} />
          </div>
        </>
      ) : null}
      {!decisions.isLoading && !decisions.isError && decisions.data?.data ? (
        <DataTablePagination
          page={page}
          size={size}
          totalElements={decisions.data.data.totalElements ?? 0}
          onPageChange={setPage}
          onSizeChange={(newSize) => {
            setSize(newSize);
            setPage(0);
          }}
        />
      ) : null}
    </div>
  );
}

function DecisionLogTable({ decisions }: { readonly decisions: AuthzDecisionView[] }) {
  const t = useTranslations("auditLog");
  const dt = useDateTimeFormatter();
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>{t("decision.table.decidedAt")}</TableHead>
          <TableHead>{t("decision.table.action")}</TableHead>
          <TableHead>{t("decision.table.allowed")}</TableHead>
          <TableHead>{t("decision.table.mode")}</TableHead>
          <TableHead>{t("decision.table.decisionId")}</TableHead>
          <TableHead>{t("decision.table.policyRevision")}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {decisions.map((decision) => (
          <TableRow key={decision.id ?? decision.decisionId ?? "unknown"}>
            <TableCell className="whitespace-nowrap font-mono text-xs text-muted-foreground">
              {decision.decidedAt ? dt.dateTime(decision.decidedAt) : t("dash")}
            </TableCell>
            <TableCell className="max-w-64">
              <span className="truncate" title={decision.action}>
                {decision.action ?? t("dash")}
              </span>
            </TableCell>
            <TableCell>
              {decision.allowed ? (
                <Badge variant="default">{t("decision.allow")}</Badge>
              ) : (
                <Badge variant="destructive">{t("decision.deny")}</Badge>
              )}
            </TableCell>
            <TableCell>
              {decision.degraded ? (
                <Badge variant="secondary">{t("decision.degraded")}</Badge>
              ) : (
                <span className="text-muted-foreground text-sm">{t("decision.normal")}</span>
              )}
            </TableCell>
            <TableCell className="max-w-40">
              <span className="block truncate font-mono text-xs text-muted-foreground" title={decision.decisionId}>
                {decision.decisionId ?? t("dash")}
              </span>
            </TableCell>
            <TableCell className="max-w-40">
              <span className="block truncate font-mono text-xs text-muted-foreground" title={decision.policyRevision}>
                {decision.policyRevision ?? t("dash")}
              </span>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

function DecisionLogCards({ decisions }: { readonly decisions: AuthzDecisionView[] }) {
  const t = useTranslations("auditLog");
  const dt = useDateTimeFormatter();
  return (
    <div className="space-y-2">
      {decisions.map((decision) => (
        <div key={decision.id ?? decision.decisionId ?? "unknown"} className="rounded-lg border p-3">
          <div className="flex items-start justify-between gap-3">
            <div className="flex min-w-0 flex-col gap-1">
              <div className="flex flex-wrap items-center gap-2">
                {decision.allowed ? (
                  <Badge variant="default">{t("decision.allow")}</Badge>
                ) : (
                  <Badge variant="destructive">{t("decision.deny")}</Badge>
                )}
                {decision.degraded ? <Badge variant="secondary">{t("decision.degraded")}</Badge> : null}
              </div>
              <p className="truncate text-sm" title={decision.action}>
                {decision.action ?? t("dash")}
              </p>
              <p className="font-mono text-muted-foreground text-xs" title={decision.decisionId}>
                {t("decision.decisionLine", { id: decision.decisionId ?? t("dash") })}
              </p>
              <p className="font-mono text-muted-foreground text-xs">
                {decision.decidedAt ? dt.dateTime(decision.decidedAt) : t("dash")}
              </p>
            </div>
          </div>
          <p className="mt-2 truncate font-mono text-xs text-muted-foreground" title={decision.policyRevision}>
            {t("decision.revisionLine", { id: decision.policyRevision ?? t("dash") })}
          </p>
        </div>
      ))}
    </div>
  );
}

function DecisionLogSkeleton() {
  return (
    <div className="space-y-2">
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-10 w-full" />
    </div>
  );
}

function DecisionLogState({
  title,
  description,
  action,
}: {
  readonly title: string;
  readonly description: string;
  readonly action?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 rounded-lg border border-dashed p-8 text-center">
      <TriangleAlertIcon className="size-8 text-muted-foreground" />
      <div>
        <h2 className="font-medium">{title}</h2>
        <p className="text-muted-foreground text-sm">{description}</p>
      </div>
      {action}
    </div>
  );
}
