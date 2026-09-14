"use client";

import { useMemo } from "react";

import Link from "next/link";

import { ArrowUpRightIcon, ChevronDownIcon, PackageIcon, WrenchIcon } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { useWorkOrderWorkLogs } from "@/features/workorders/hooks/use-workorder-detail";
import { useWorkorderPrintReport } from "@/features/workorders/hooks/use-workorder-print-report";

/**
 * Inline expansion for one workorder table row (leader feedback 2026-09-14: see
 * everything without leaving the table): previously logged work sessions (read-only,
 * from GET /{id}/work-logs) and requested spareparts with their status (from the
 * print-report aggregate). Fetches only while expanded.
 */
export function WorkorderRowExpand({ workOrderId }: { workOrderId: string }) {
  const t = useTranslations("workOrders");
  const tsr = useTranslations("sparepartRequests");
  const format = useFormatter();
  const workLogs = useWorkOrderWorkLogs(workOrderId);
  const report = useWorkorderPrintReport(workOrderId);

  const sessions = useMemo(
    () => [...(workLogs.data ?? [])].sort((a, b) => a.startTime.localeCompare(b.startTime)),
    [workLogs.data],
  );
  const parts = report.data?.parts ?? [];

  const fmt = (iso: string | null) =>
    iso
      ? format.dateTime(new Date(iso), {
          day: "numeric",
          month: "short",
          hour: "2-digit",
          minute: "2-digit",
          hour12: false,
        })
      : "-";

  const loading = workLogs.isLoading && report.isLoading;

  return (
    <div className="grid gap-4 p-1 text-sm md:grid-cols-2">
      {loading ? (
        <>
          <Skeleton className="h-20 w-full" />
          <Skeleton className="h-20 w-full" />
        </>
      ) : (
        <>
          <div className="space-y-2">
            <p className="font-medium text-xs uppercase tracking-wide text-muted-foreground">
              {t("table.expandSessions")}
            </p>
            {sessions.length === 0 ? (
              <p className="text-muted-foreground text-xs">{t("table.expandNoSessions")}</p>
            ) : (
              <ul className="space-y-2">
                {sessions.map((log, i) => (
                  <li key={log.id} className="flex items-start gap-2">
                    <WrenchIcon className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" />
                    <div className="min-w-0">
                      <p className="text-xs">
                        <span className="font-medium">{t("actionsCell.assignDialog.sessionN", { n: i + 1 })}</span> ·{" "}
                        {fmt(log.startTime)} → {log.endTime ? fmt(log.endTime) : t("detail.openSession")}
                        {log.stoppedReason ? (
                          <Badge variant="secondary" className="ml-1 text-xs">
                            {t.has(`stoppedReason.${log.stoppedReason}`)
                              ? t(`stoppedReason.${log.stoppedReason}`)
                              : log.stoppedReason}
                          </Badge>
                        ) : null}
                      </p>
                      <p className="truncate text-muted-foreground text-xs">
                        {log.activityNote}
                        {log.completionNote ? ` — ${log.completionNote}` : ""}
                      </p>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </div>
          <div className="space-y-2">
            <p className="font-medium text-xs uppercase tracking-wide text-muted-foreground">
              {t("table.expandParts")}
            </p>
            {parts.length === 0 ? (
              <p className="text-muted-foreground text-xs">{t("table.expandNoParts")}</p>
            ) : (
              <ul className="space-y-1">
                {parts.map((p) => (
                  <li key={p.id} className="flex items-center gap-2 text-xs">
                    <PackageIcon className="size-3.5 shrink-0 text-muted-foreground" />
                    <span className="font-mono">{p.materialCode ?? "-"}</span>
                    <span className="text-muted-foreground">×{p.quantity}</span>
                    <Badge variant="outline" className="text-xs">
                      {tsr.has(`status.${p.status}`) ? tsr(`status.${p.status}`) : p.status}
                    </Badge>
                  </li>
                ))}
              </ul>
            )}
            <Button asChild variant="ghost" size="sm" className="h-7 px-2 text-xs">
              <Link href={`/dashboard/workorders/${workOrderId}`}>
                {t("table.expandOpenDetail")}
                <ArrowUpRightIcon className="ml-1 size-3" />
              </Link>
            </Button>
          </div>
        </>
      )}
    </div>
  );
}

/** Chevron cell content toggling row expansion. */
export function ExpandToggle({ expanded, onToggle }: { expanded: boolean; onToggle: () => void }) {
  const t = useTranslations("workOrders");
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon"
      className="size-7"
      aria-label={t("table.expandAria")}
      aria-expanded={expanded}
      onClick={onToggle}
    >
      <ChevronDownIcon className={`size-4 transition-transform ${expanded ? "rotate-180" : ""}`} />
    </Button>
  );
}
