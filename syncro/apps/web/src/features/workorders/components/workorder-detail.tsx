"use client";

import { useMemo } from "react";

import Link from "next/link";

import { useQuery } from "@tanstack/react-query";
import { ArrowLeftIcon, FileTextIcon, PackageIcon, WrenchIcon } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { useListUsersMaster } from "@/features/organization/hooks/use-users";
import { RatingPanel } from "@/features/workorders/components/rating-panel";
import { WorkorderActionsCell } from "@/features/workorders/components/workorder-actions-cell";
import {
  useWorkOrderAssignments,
  useWorkOrderCreatedInstant,
  useWorkOrderWorkLogs,
} from "@/features/workorders/hooks/use-workorder-detail";
import { useWorkorderPrintReport } from "@/features/workorders/hooks/use-workorder-print-report";
import { useListMachineResponsibilities } from "@/lib/api/generated/syncro";
import { syncroFetch } from "@/lib/api/orval-mutator";

interface CategoryOption {
  code: string;
  label: string;
}

interface SparepartRequestRow {
  id: string;
  workOrderId: string | null;
  materialCode: string | null;
  quantity: number;
  status: string;
  requestedAt: string;
  updatedAt: string;
}

/** Requests linked to this WO — read-only timeline (PP date → last status change). */
function useWorkOrderSparepartRequests(workOrderId: string) {
  return useQuery<SparepartRequestRow[]>({
    queryKey: ["/api/v1/sparepart-requests", "wo", workOrderId],
    queryFn: async () => {
      const res = await syncroFetch<{ data: { items: SparepartRequestRow[] } }>(
        "/api/v1/sparepart-requests?page=0&size=100",
        { method: "GET" },
      );
      return (res.data.items ?? []).filter((r) => r.workOrderId === workOrderId);
    },
    staleTime: 15_000,
    retry: false,
  });
}

/**
 * Workorder detail page (QA 2026-09-14, finding #4): everything the leader needs to
 * verify or backfill a job in one view — header info, assigned technicians (from
 * GET /assignments, not the single legacy name column), the full work-log timeline
 * with per-session duration, and linked sparepart requests. "Tugaskan & Kerjakan"
 * (multi-session backfill dialog) lives here too. Data is composed from existing
 * endpoints — the backend has no GET /{id}.
 */
export function WorkorderDetailPageContent({ workOrderId }: { workOrderId: string }) {
  const t = useTranslations("workOrders");
  const tc = useTranslations("common");
  const tsr = useTranslations("sparepartRequests");
  const format = useFormatter();

  const report = useWorkorderPrintReport(workOrderId);
  const assignments = useWorkOrderAssignments(workOrderId);
  const workLogs = useWorkOrderWorkLogs(workOrderId);
  // READ-ONLY by domain boundary (leader decision 2026-09-14): the workorder cockpit
  // serves maintenance leaders (machinery/utility/workshop). Requesting a part stays a
  // WO action; every status change (ack/proses/ready/diterima/tutup) belongs to the
  // warehouse/inventory flow and must happen there, never here. This section mirrors
  // the request's journey (diajukan → pembaruan status terakhir) without any control.
  const partsQuery = useWorkOrderSparepartRequests(workOrderId);
  // Exact creation instant (the print-report header's createdAt is date-only → wrong
  // backdate floor; backend rejects a session starting before the real created_at).
  const { data: createdInstant } = useWorkOrderCreatedInstant(workOrderId);
  const { data: usersRes } = useListUsersMaster();
  const { data: categoriesRes } = useQuery<CategoryOption[]>({
    queryKey: ["/api/v1/work-order-categories"],
    queryFn: async () => {
      const res = await syncroFetch<{ data: CategoryOption[] }>("/api/v1/work-order-categories", { method: "GET" });
      return res.data;
    },
    staleTime: 60_000,
  });

  // Technician picker scoped to the WO's machine via machine responsibilities
  // (GET /machine-responsibilities?machineId). Empty machine responsibility set →
  // fall back to all technicians so the dialog is never dead-ended (server keeps the
  // authoritative assign gate).
  const header = report.data?.header;
  const machineId = header?.machineId;
  const { data: respRes } = useListMachineResponsibilities(
    { machineId, pageable: { page: 0, size: 100 } },
    { query: { enabled: Boolean(machineId), staleTime: 30_000 } },
  );
  const responsibleUserIds = useMemo(() => {
    const set = new Set<string>();
    for (const r of respRes?.data?.items ?? []) {
      if (r.userId) set.add(r.userId);
    }
    return set;
  }, [respRes]);

  const allAssignableUsers = (usersRes?.data ?? []).filter(
    (u) => u.applicationRole === "TECHNICIAN" || u.applicationRole === "STAFF_MAINTENANCE",
  );
  // Already-assigned technicians must stay selectable even when not in the machine's
  // responsibility rows — the leader re-opens the dialog to add their next session.
  const assignedIds = new Set((assignments.data ?? []).map((a) => a.technicianId));
  const scopeActive = responsibleUserIds.size > 0;
  const assignableUsers = scopeActive
    ? allAssignableUsers.filter((u) => responsibleUserIds.has(u.id) || assignedIds.has(u.id))
    : allAssignableUsers;
  const nameByUserId = useMemo(() => {
    const map = new Map<string, string>();
    for (const u of usersRes?.data ?? []) map.set(u.id, u.displayName ?? u.loginIdentifier);
    return map;
  }, [usersRes]);

  const sessions = useMemo(
    () => [...(workLogs.data ?? [])].sort((a, b) => a.startTime.localeCompare(b.startTime)),
    [workLogs.data],
  );

  const formatDateTime = (iso: string | null) =>
    iso
      ? format.dateTime(new Date(iso), {
          day: "numeric",
          month: "short",
          year: "numeric",
          hour: "2-digit",
          minute: "2-digit",
          hour12: false,
        })
      : "-";

  const durationHours = (startIso: string, endIso: string | null) => {
    if (!endIso) return null;
    const ms = new Date(endIso).getTime() - new Date(startIso).getTime();
    return Math.max(0, ms / 3_600_000);
  };

  if (report.isLoading) {
    return (
      <div className="mx-auto w-full max-w-4xl space-y-4 p-6">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-32 w-full" />
        <Skeleton className="h-48 w-full" />
      </div>
    );
  }

  if (report.isError || !header) {
    return (
      <div className="mx-auto w-full max-w-4xl space-y-4 p-6">
        <BackLink />
        <p className="text-muted-foreground text-sm">
          {report.isError ? t("detail.loadFailed") : t("detail.notFound")}
        </p>
      </div>
    );
  }

  const statusLabel = t.has(`status.${header.status}`) ? t(`status.${header.status}`) : header.status;
  const narrative = report.data?.narrative;
  const cpk = report.data?.cpk;
  const parts = partsQuery.data ?? [];
  // Executors = everyone who worked this WO (assigned + logged), for the technician rating.
  const executorPool = Array.from(
    new Set([
      ...(assignments.data ?? []).map((a) => a.technicianId),
      ...(workLogs.data ?? []).map((l) => l.technicianId),
    ]),
  );

  return (
    <div className="mx-auto w-full max-w-4xl space-y-5 p-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <BackLink />
        {/* Full action set (assign+work, transitions, request part, report, print) — the
            same menu as the table row with the redundant "Detail" entry hidden. The
            ActionsCell owns the assign dialog + its refresh invalidations, so operating
            a workorder never needs another screen. */}
        <WorkorderActionsCell
          workOrderId={workOrderId}
          status={header.status}
          assignableUsers={assignableUsers}
          isLoadingUsers={!usersRes}
          createdAt={createdInstant ?? header.createdAt}
          hideDetail
        />
      </div>

      {/* Header info */}
      <section className="space-y-3 rounded-lg border p-4">
        <div className="flex flex-wrap items-center gap-2">
          <h2 className="font-mono font-semibold text-lg">{header.id}</h2>
          <Badge className={statusBadgeClass(header.status)}>{statusLabel}</Badge>
        </div>
        <dl className="grid grid-cols-2 gap-x-6 gap-y-2 text-sm sm:grid-cols-3">
          <Field label={t("machine")}>
            {[header.machineCode, header.machineName].filter(Boolean).join(" · ") || t("dash")}
          </Field>
          <Field label={t("category")}>
            {header.categoryCode
              ? `${header.categoryCode} · ${header.categoryLabel ?? categoriesRes?.find((c) => c.code === header.categoryCode)?.label ?? ""}`
              : t("dash")}
          </Field>
          <Field label={tc("plant")}>{header.plantCode || t("dash")}</Field>
          <Field label={tc("createdAt")}>{formatDateTime(createdInstant ?? header.createdAt)}</Field>
          <Field label={tc("updatedAt")}>{formatDateTime(header.updatedAt)}</Field>
          <div className="col-span-2 sm:col-span-3">
            <dt className="text-muted-foreground text-xs">{t("table.problem")}</dt>
            <dd className="text-sm">{header.description || t("dash")}</dd>
          </div>
        </dl>
      </section>

      {/* Technicians */}
      <section className="space-y-2 rounded-lg border p-4">
        <h3 className="font-semibold text-sm">{t("detail.technicians")}</h3>
        {assignments.isLoading ? (
          <Skeleton className="h-6 w-48" />
        ) : (assignments.data?.length ?? 0) === 0 ? (
          <p className="text-muted-foreground text-sm">{t("detail.noTechnicians")}</p>
        ) : (
          <ul className="flex flex-wrap gap-2">
            {(assignments.data ?? []).map((a) => (
              <li key={a.id} className="flex items-center gap-2 rounded-md border px-2 py-1 text-sm">
                <span>{nameByUserId.get(a.technicianId) ?? a.technicianId}</span>
                <Badge variant={a.isActive ? "default" : "secondary"} className="text-xs">
                  {a.isActive ? t("detail.activeBadge") : t("detail.droppedBadge")}
                </Badge>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* Work timeline */}
      <section className="space-y-2 rounded-lg border p-4">
        <h3 className="font-semibold text-sm">{t("detail.timeline")}</h3>
        {workLogs.isLoading ? (
          <Skeleton className="h-6 w-48" />
        ) : sessions.length === 0 ? (
          <p className="text-muted-foreground text-sm">{t("detail.noTimeline")}</p>
        ) : (
          <ol className="space-y-3">
            {sessions.map((log, index) => {
              const hours = durationHours(log.startTime, log.endTime);
              return (
                <li key={log.id} className="rounded-md border bg-muted/30 p-3">
                  <div className="flex flex-wrap items-center gap-2 text-sm">
                    <WrenchIcon className="size-3.5 text-muted-foreground" />
                    <span className="font-medium">{t("actionsCell.assignDialog.sessionN", { n: index + 1 })}</span>
                    <span>
                      {formatDateTime(log.startTime)} →{" "}
                      {log.endTime ? formatDateTime(log.endTime) : t("detail.openSession")}
                    </span>
                    {hours !== null ? (
                      <Badge variant="outline" className="text-xs">
                        {t("detail.duration", { hours: hours.toFixed(1) })}
                      </Badge>
                    ) : null}
                    {log.stoppedReason ? (
                      <Badge variant="secondary" className="text-xs">
                        {t.has(`stoppedReason.${log.stoppedReason}`)
                          ? t(`stoppedReason.${log.stoppedReason}`)
                          : log.stoppedReason}
                      </Badge>
                    ) : null}
                  </div>
                  <p className="text-muted-foreground text-xs">
                    {t("detail.sessionBy", { name: nameByUserId.get(log.technicianId) ?? log.technicianId })}
                  </p>
                  {log.activityNote ? <p className="mt-1 text-sm">{log.activityNote}</p> : null}
                  {log.completionNote ? (
                    <p className="text-muted-foreground text-xs italic">{log.completionNote}</p>
                  ) : null}
                </li>
              );
            })}
          </ol>
        )}
      </section>

      {/* Sparepart requests */}
      <section className="space-y-2 rounded-lg border p-4">
        <h3 className="font-semibold text-sm">{t("detail.parts")}</h3>
        {partsQuery.isLoading ? (
          <Skeleton className="h-6 w-48" />
        ) : parts.length === 0 ? (
          <p className="text-muted-foreground text-sm">{t("detail.noParts")}</p>
        ) : (
          <ul className="space-y-2">
            {parts.map((p) => (
              <li key={p.id} className="space-y-0.5">
                <div className="flex flex-wrap items-center gap-2 text-sm">
                  <PackageIcon className="size-3.5 text-muted-foreground" />
                  <Link
                    href={`/dashboard/sparepart-requests/${p.id}`}
                    className="font-mono hover:underline"
                    title={t("detail.openWarehouse")}
                  >
                    {p.materialCode ?? "-"}
                  </Link>
                  <span className="text-muted-foreground">×{p.quantity}</span>
                  <Badge variant="outline" className="text-xs">
                    {tsr.has(`status.${p.status}`) ? tsr(`status.${p.status}`) : p.status}
                  </Badge>
                </div>
                <p className="text-muted-foreground text-xs">
                  {t("detail.partRequested", { date: formatDateTime(p.requestedAt) })}
                  {p.status !== "REQUESTED" && p.status !== "PENDING_COMPLETION"
                    ? ` · ${t("detail.partUpdated", { date: formatDateTime(p.updatedAt) })}`
                    : ""}
                </p>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* Report (full narrative + CPK/FMEA/stop-time — already in the print aggregate) */}
      <section className="space-y-2 rounded-lg border p-4 text-sm">
        <h3 className="font-semibold">{t("detail.report")}</h3>
        {report.isLoading ? (
          <Skeleton className="h-6 w-48" />
        ) : (
          <div className="space-y-3">
            <ReportField label={t("actionsCell.reportDialog.chronological")} value={narrative?.reportChronological} />
            <ReportField label={t("actionsCell.reportDialog.analyze")} value={narrative?.reportAnalyze} />
            <ReportField label={t("actionsCell.reportDialog.corrective")} value={narrative?.reportCorrective} />
            <ReportField label={t("actionsCell.reportDialog.preventive")} value={narrative?.reportPreventive} />
            {cpk?.fmeaFailureType || cpk?.cpk || cpk?.stopTimeReason ? (
              <div className="grid grid-cols-2 gap-x-6 gap-y-2 sm:grid-cols-3">
                {cpk?.fmeaFailureType ? <Field label={t("detail.fmea")}>{cpk.fmeaFailureType}</Field> : null}
                {cpk?.cpk != null ? (
                  <Field label={t("detail.cpk")}>
                    {cpk.cpk}
                    {cpk.cpCkLower && cpk.cpCkUpper ? ` (${cpk.cpCkLower}–${cpk.cpCkUpper})` : ""}
                  </Field>
                ) : null}
                {cpk?.stopTimeReason ? (
                  <Field label={t("detail.stopTime")}>
                    {cpk.stopTimeReason}
                    {cpk.stopTimeDetail ? ` — ${cpk.stopTimeDetail}` : ""}
                  </Field>
                ) : null}
              </div>
            ) : null}
            {cpk?.cpkPdfPresignedUrl ? (
              <a
                href={cpk.cpkPdfPresignedUrl}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center gap-1 text-xs text-primary hover:underline"
              >
                <FileTextIcon className="size-3.5" />
                {t("detail.cpkPdf")}
              </a>
            ) : null}
            {!narrative?.reportChronological &&
            !narrative?.reportAnalyze &&
            !narrative?.reportCorrective &&
            !narrative?.reportPreventive ? (
              <p className="text-muted-foreground text-sm">{t("detail.noReport")}</p>
            ) : null}
          </div>
        )}
      </section>

      {/* Ratings: technician (section leader) + production (PRODUCTION_LEADER) — the
          shared panel, interactive for entitled roles, immutable read-only otherwise.
          The server only accepts submissions on CLOSED workorders. */}
      <section className="space-y-2 rounded-lg border p-4">
        <h3 className="font-semibold text-sm">{t("detail.ratings")}</h3>
        {header.status === "CLOSED" ? (
          <RatingPanel workorderId={workOrderId} executorPool={executorPool} />
        ) : (
          <p className="text-muted-foreground text-sm">{t("detail.ratingsLocked")}</p>
        )}
      </section>
    </div>
  );
}

function ReportField({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div>
      <p className="text-muted-foreground text-xs">{label}</p>
      <p className="whitespace-pre-line">{value ? value : <span className="text-muted-foreground">—</span>}</p>
    </div>
  );
}

function BackLink() {
  const t = useTranslations("workOrders");
  return (
    <Button asChild variant="ghost" size="sm" className="-ml-2 text-muted-foreground">
      <Link href="/dashboard/workorders">
        <ArrowLeftIcon className="mr-1 size-4" />
        {t("detail.back")}
      </Link>
    </Button>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-muted-foreground text-xs">{label}</dt>
      <dd className="text-sm">{children}</dd>
    </div>
  );
}

function statusBadgeClass(status: string): string {
  switch (status) {
    case "OPEN":
      return "status-badge-critical";
    case "IN_PROGRESS":
    case "PENDING_REVIEW":
      return "status-badge-warning";
    case "PENDING_SPAREPART":
      return "status-badge-info";
    case "CLOSED":
      return "status-badge-healthy";
    default:
      return "status-badge-neutral";
  }
}
