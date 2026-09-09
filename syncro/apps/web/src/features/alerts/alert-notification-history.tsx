"use client";

import { useState } from "react";

import { ChevronDownIcon, ChevronUpIcon, CopyIcon } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";
import { toast } from "sonner";

import { type EscalationStep, EscalationTimeline } from "@/components/syncro/escalation-timeline";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type {
  AlertNotificationHistoryResponse,
  AlertViewStatus,
  AuditLogEntryView,
  NotificationAttemptView,
  NotificationJobView,
} from "@/lib/api/generated/model";
import { SyncroApiError } from "@/lib/api/orval-mutator";

export type { AlertNotificationHistoryResponse, NotificationAttemptView, NotificationJobView };

// ---------------------------------------------------------------------------
// Normalization — generated DTO fields are optional; normalize once at the
// boundary so the render tree can keep non-null assumptions (DW-85).
// ---------------------------------------------------------------------------

type NormalizedAttempt = Required<NotificationAttemptView>;
type NormalizedJob = Omit<Required<NotificationJobView>, "escalationLevel" | "status" | "attempts"> & {
  escalationLevel: string;
  status: string;
  attempts: NormalizedAttempt[];
};

function normalizeJob(job: NotificationJobView): NormalizedJob {
  return {
    id: job.id ?? "",
    alertId: job.alertId ?? "",
    escalationLevel: job.escalationLevel ?? "",
    status: job.status ?? "",
    recipientUserId: job.recipientUserId ?? "",
    recipientDisplayName: job.recipientDisplayName ?? "",
    recipientPhoneMasked: job.recipientPhoneMasked ?? "",
    attemptCount: job.attemptCount ?? 0,
    maxAttempts: job.maxAttempts ?? 0,
    sentAt: job.sentAt ?? "",
    createdAt: job.createdAt ?? "",
    updatedAt: job.updatedAt ?? "",
    nextAttemptAt: job.nextAttemptAt ?? "",
    errorDetail: job.errorDetail ?? "",
    traceId: job.traceId ?? "",
    attempts: (job.attempts ?? []).map((a) => ({
      attemptNumber: a.attemptNumber ?? 0,
      status: a.status ?? "",
      attemptedAt: a.attemptedAt ?? "",
      responseDetail: a.responseDetail ?? "",
      traceId: a.traceId ?? "",
    })),
  };
}

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

export interface AlertNotificationHistoryProps {
  readonly history: AlertNotificationHistoryResponse | undefined;
  readonly auditEntries: AuditLogEntryView[] | undefined;
  readonly isLoadingHistory: boolean;
  readonly isLoadingAudit: boolean;
  readonly errorHistory: unknown | null;
  readonly errorAudit: unknown | null;
  readonly onRetryHistory?: () => void;
  readonly onRetryAudit?: () => void;
  readonly alertStatus?: AlertViewStatus;
  readonly alertCreatedAt?: string | null;
}

// ---------------------------------------------------------------------------
// Constants & Helpers
// ---------------------------------------------------------------------------

const ESCALATION_ORDER = ["TECHNICIAN", "STAFF", "LEADER", "SPV", "MANAGER"] as const;

type Formatter = ReturnType<typeof useFormatter>;
type Translator = ReturnType<typeof useTranslations>;

function tsOf(format: Formatter, iso?: string | null): string {
  if (!iso) return "-";
  try {
    return format.dateTime(new Date(iso), { dateStyle: "medium", timeStyle: "short" });
  } catch {
    return iso;
  }
}

function timeOf(format: Formatter, iso?: string | null): string {
  if (!iso) return "-";
  try {
    return format.dateTime(new Date(iso), { timeStyle: "short" });
  } catch {
    return iso;
  }
}

function truncate120(value: string | null | undefined) {
  if (!value) return null;
  return value.length > 120 ? `${value.slice(0, 120)}…` : value;
}

async function copyText(value: string, t: Translator) {
  try {
    await navigator.clipboard.writeText(value);
    toast.success(t("notificationHistory.copied"));
  } catch {
    toast.error(t("notificationHistory.copyFailed"));
  }
}

function extractTraceId(error: unknown): string | null {
  if (error instanceof SyncroApiError) {
    const payload = error.payload as Record<string, unknown> | null;
    if (payload && typeof payload.traceId === "string") return payload.traceId as string;
    if (payload && typeof (payload as Record<string, unknown>).trace_id === "string")
      return (payload as Record<string, unknown>).trace_id as string;
  }
  if (
    error &&
    typeof error === "object" &&
    "traceId" in error &&
    typeof (error as Record<string, unknown>).traceId === "string"
  ) {
    return (error as Record<string, unknown>).traceId as string;
  }
  return null;
}

// Backend message/code strings are contract data (rendered as-is); only the
// terminal fallback is translated (errors.generic).
function extractErrorMessage(error: unknown, te: Translator): string {
  if (error instanceof SyncroApiError) {
    const payload = error.payload as Record<string, unknown> | null;
    if (payload && typeof payload.message === "string") return payload.message as string;
    if (payload && typeof payload.code === "string")
      return `${payload.code as string}: ${payload.message ?? te("generic")}`;
  }
  if (error instanceof Error) return error.message;
  return te("generic");
}

// ---------------------------------------------------------------------------
// Main composition — now renders 3 first-class Cards per page-spec §3
// ---------------------------------------------------------------------------

export function AlertNotificationHistory({
  history,
  auditEntries,
  isLoadingHistory,
  isLoadingAudit,
  errorHistory,
  errorAudit,
  onRetryHistory,
  onRetryAudit,
  alertStatus = "OPEN",
  alertCreatedAt = null,
}: AlertNotificationHistoryProps) {
  const t = useTranslations("alerts");
  // Enforce escalation order on frontend as well (AC1) — do not rely solely on backend
  const sortedItems = (history?.items ?? []).map(normalizeJob).sort((a, b) => {
    const ia = ESCALATION_ORDER.indexOf(a.escalationLevel as (typeof ESCALATION_ORDER)[number]);
    const ib = ESCALATION_ORDER.indexOf(b.escalationLevel as (typeof ESCALATION_ORDER)[number]);
    const rankA = ia === -1 ? 99 : ia;
    const rankB = ib === -1 ? 99 : ib;
    if (rankA !== rankB) return rankA - rankB;
    const ta = new Date(a.createdAt).getTime();
    const tb = new Date(b.createdAt).getTime();
    if (Number.isNaN(ta) || Number.isNaN(tb)) return 0;
    return ta - tb;
  });

  const steps: EscalationStep[] = sortedItems.map((job) => ({
    level: job.escalationLevel,
    recipientDisplayName: job.recipientDisplayName,
    recipientPhoneMasked: job.recipientPhoneMasked,
    status: mapStatus(job.status, job.nextAttemptAt),
    rawStatus: job.status,
    timestamp: job.status === "ESCALATED" ? job.updatedAt : job.sentAt || job.updatedAt || job.createdAt,
    nextSendAt: job.nextAttemptAt || undefined,
    deliveryResult: job.errorDetail ? job.errorDetail.slice(0, 512) : undefined,
    traceId: job.traceId || undefined,
  }));

  return (
    <div className="space-y-6">
      {/* Escalation Timeline — first-class section */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t("notificationHistory.timelineTitle")}</CardTitle>
          <CardDescription>{t("notificationHistory.timelineDescription")}</CardDescription>
        </CardHeader>
        <CardContent>
          <TimelineSection
            steps={steps}
            alertStatus={alertStatus}
            isLoading={isLoadingHistory}
            error={errorHistory}
            isEmpty={sortedItems.length === 0 && !isLoadingHistory && !errorHistory}
            onRetry={onRetryHistory}
            alertCreatedAt={alertCreatedAt}
          />
        </CardContent>
      </Card>

      {/* Notification history table */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t("notificationHistory.historyTitle")}</CardTitle>
          <CardDescription>{t("notificationHistory.historyDescription")}</CardDescription>
        </CardHeader>
        <CardContent>
          <HistoryTableSection
            items={sortedItems}
            isLoading={isLoadingHistory}
            error={errorHistory}
            onRetry={onRetryHistory}
          />
        </CardContent>
      </Card>

      {/* Audit evidence */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t("notificationHistory.auditTitle")}</CardTitle>
          <CardDescription>{t("notificationHistory.auditDescription")}</CardDescription>
        </CardHeader>
        <CardContent>
          <AuditEvidenceSection
            entries={auditEntries}
            isLoading={isLoadingAudit}
            error={errorAudit}
            onRetry={onRetryAudit}
          />
        </CardContent>
      </Card>
    </div>
  );
}

function mapStatus(raw: string, nextAttemptAt?: string | null): EscalationStep["status"] {
  switch (raw) {
    case "SENT":
      return "sent";
    case "ESCALATED":
      return "sent";
    case "PENDING":
      // AC2: PENDING → pending/queued (if nextAttemptAt present, pending else queued)
      return nextAttemptAt ? "pending" : "queued";
    case "ROUTING_FAILED":
      return "failed";
    case "EXHAUSTED":
      return "failed";
    case "CANCELLED":
      return "stopped";
    case "RATE_LIMITED":
      return "rate-limited";
    default:
      // unknown/future per AC2 → neutral styling with raw label visible — never crash
      if (raw.includes("RATE_LIMITED") || raw.includes("CIRCUIT")) return "rate-limited";
      return "stopped";
  }
}

// ---------------------------------------------------------------------------
// Timeline Section (with loading/empty/error states per AC 6)
// ---------------------------------------------------------------------------

function TimelineSection({
  steps,
  alertStatus,
  isLoading,
  error,
  isEmpty,
  onRetry,
  alertCreatedAt,
}: {
  steps: EscalationStep[];
  alertStatus: "OPEN" | "ACKNOWLEDGED" | "RESOLVED";
  isLoading: boolean;
  error: unknown | null;
  isEmpty: boolean;
  onRetry?: () => void;
  alertCreatedAt?: string | null;
}) {
  const t = useTranslations("alerts");
  const tc = useTranslations("common");
  const te = useTranslations("errors");
  const format = useFormatter();
  if (isLoading) {
    return (
      <div className="space-y-3">
        <div className="h-4 w-32 animate-pulse rounded bg-muted" />
        <div className="h-20 w-full animate-pulse rounded bg-muted" />
      </div>
    );
  }
  if (error) {
    const traceId = extractTraceId(error);
    const message = extractErrorMessage(error, te);
    const isSuperAdmin = typeof window !== "undefined" && document.cookie.includes("SUPER_ADMIN");
    // Use role-agnostic detail but show traceId where available per AC6
    return (
      <div className="space-y-2 rounded-md border border-destructive/30 p-4">
        <p className="text-sm font-medium text-destructive">{t("notificationHistory.timelineLoadFailed")}</p>
        <p className="text-xs text-muted-foreground">{isSuperAdmin ? message : te("generic")}</p>
        {traceId ? (
          <p className="font-mono-tight text-xs text-muted-foreground">
            {t("notificationHistory.traceIdLine", { traceId })}
          </p>
        ) : null}
        {onRetry ? (
          <Button variant="outline" size="sm" onClick={onRetry}>
            {tc("retry")}
          </Button>
        ) : null}
      </div>
    );
  }
  if (isEmpty) {
    return (
      <div className="p-4 text-center">
        <p className="text-sm text-muted-foreground">{t("notificationHistory.emptyTimeline")}</p>
        {alertCreatedAt ? (
          <p className="font-mono-tight mt-1 text-xs text-muted-foreground">
            {t("notificationHistory.alertCreatedLine", { datetime: tsOf(format, alertCreatedAt) })}
          </p>
        ) : null}
      </div>
    );
  }

  return <EscalationTimeline steps={steps} alertStatus={alertStatus} expandedByDefault />;
}

// ---------------------------------------------------------------------------
// History Table Section
// ---------------------------------------------------------------------------

function HistoryTableSection({
  items,
  isLoading,
  error,
  onRetry,
}: {
  items: NormalizedJob[] | undefined;
  isLoading: boolean;
  error: unknown | null;
  onRetry?: () => void;
}) {
  const t = useTranslations("alerts");
  const tc = useTranslations("common");
  const te = useTranslations("errors");
  const format = useFormatter();
  const [expandedAttempts, setExpandedAttempts] = useState<Set<string>>(new Set());
  const [expandedErrors, setExpandedErrors] = useState<Set<string>>(new Set());

  function toggleAttempts(id: string) {
    setExpandedAttempts((cur) => {
      const next = new Set(cur);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }
  function toggleError(id: string) {
    setExpandedErrors((cur) => {
      const next = new Set(cur);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  if (isLoading) {
    return (
      <div className="space-y-2 p-4">
        <div className="h-4 w-48 animate-pulse rounded bg-muted" />
        <div className="h-32 w-full animate-pulse rounded bg-muted" />
      </div>
    );
  }
  if (error) {
    const traceId = extractTraceId(error);
    const message = extractErrorMessage(error, te);
    return (
      <div className="space-y-2 rounded-md border border-destructive/30 p-4">
        <p className="text-sm font-medium text-destructive">{t("notificationHistory.historyLoadFailed")}</p>
        <p className="text-xs text-muted-foreground">{message}</p>
        {traceId ? (
          <p className="font-mono-tight text-xs text-muted-foreground">
            {t("notificationHistory.traceIdLine", { traceId })}
          </p>
        ) : null}
        {onRetry ? (
          <Button variant="outline" size="sm" onClick={onRetry}>
            {tc("retry")}
          </Button>
        ) : null}
      </div>
    );
  }
  if (!items || items.length === 0) {
    return (
      <div className="p-4 text-center">
        <p className="text-sm text-muted-foreground">{t("notificationHistory.emptyHistory")}</p>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      {/* Desktop table */}
      <div className="hidden overflow-hidden rounded-md border md:block">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("notificationHistory.headerLevel")}</TableHead>
              <TableHead>{t("notificationHistory.headerRecipient")}</TableHead>
              <TableHead>{t("notificationHistory.headerPhone")}</TableHead>
              <TableHead>{tc("status")}</TableHead>
              <TableHead>{t("notificationHistory.headerAttempts")}</TableHead>
              <TableHead>{t("notificationHistory.headerTime")}</TableHead>
              <TableHead>{t("notificationHistory.headerDetail")}</TableHead>
              <TableHead>{t("notificationHistory.headerTrace")}</TableHead>
              <TableHead className="w-10" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {items.map((job) => {
              const isAttOpen = expandedAttempts.has(job.id);
              const isErrOpen = expandedErrors.has(job.id);
              const ts = job.sentAt || job.createdAt;
              return (
                <TableRow key={job.id}>
                  <TableCell className="font-medium">{job.escalationLevel}</TableCell>
                  <TableCell className="max-w-36 truncate" title={job.recipientDisplayName ?? undefined}>
                    {job.recipientDisplayName ?? "-"}
                  </TableCell>
                  <TableCell className="font-mono-tight" title={job.recipientPhoneMasked ?? undefined}>
                    {job.recipientPhoneMasked ?? "-"}
                  </TableCell>
                  <TableCell>
                    <Badge variant="outline" className="font-mono-tight">
                      {job.status}
                    </Badge>
                  </TableCell>
                  <TableCell className="tabular-nums">
                    {job.attemptCount}/{job.maxAttempts}
                  </TableCell>
                  <TableCell className="font-mono-tight whitespace-nowrap text-xs">{tsOf(format, ts)}</TableCell>
                  <TableCell className="max-w-48">
                    {job.errorDetail ? (
                      <span className="break-words text-xs" title={job.errorDetail}>
                        {isErrOpen ? job.errorDetail.slice(0, 512) : truncate120(job.errorDetail)}
                        {job.errorDetail.length > 120 ? (
                          <button
                            type="button"
                            className="ml-1 text-primary underline"
                            onClick={() => toggleError(job.id)}
                          >
                            {isErrOpen ? t("notificationHistory.less") : t("notificationHistory.more")}
                          </button>
                        ) : null}
                      </span>
                    ) : (
                      <span className="text-xs text-muted-foreground">—</span>
                    )}
                  </TableCell>
                  <TableCell className="font-mono-tight text-xs">
                    {job.traceId ? (
                      <span className="inline-flex items-center gap-1">
                        <span className="max-w-20 truncate" title={job.traceId}>
                          {job.traceId.slice(0, 8)}…
                        </span>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="size-6"
                          aria-label={t("notificationHistory.copyTraceAria")}
                          onClick={() => copyText(job.traceId ?? "", t)}
                        >
                          <CopyIcon className="size-3" />
                        </Button>
                      </span>
                    ) : (
                      "—"
                    )}
                  </TableCell>
                  <TableCell>
                    {job.attempts.length > 0 ? (
                      <Button
                        variant="ghost"
                        size="sm"
                        className="size-8 p-0"
                        onClick={() => toggleAttempts(job.id)}
                        aria-label={t("notificationHistory.toggleAttemptsAria")}
                      >
                        {isAttOpen ? <ChevronUpIcon className="size-4" /> : <ChevronDownIcon className="size-4" />}
                      </Button>
                    ) : null}
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
        {/* Attempts rendered as separate row below table for simplicity - use expanded area under table */}
        {items.map((job) =>
          expandedAttempts.has(job.id) && job.attempts.length > 0 ? (
            <div key={`${job.id}-attempts`} className="border-t p-3">
              <AttemptTable attempts={job.attempts} />
            </div>
          ) : null,
        )}
      </div>

      {/* Mobile stacked cards */}
      <div className="space-y-2 md:hidden">
        {items.map((job) => {
          const isAttOpen = expandedAttempts.has(job.id);
          const ts = job.sentAt || job.createdAt;
          return (
            <div key={job.id} className="space-y-1.5 rounded-lg border p-3">
              <div className="flex items-center justify-between gap-2">
                <span className="text-sm font-semibold">{job.escalationLevel}</span>
                <Badge variant="outline" className="font-mono-tight text-xs">
                  {job.status}
                </Badge>
              </div>
              <p className="text-sm">
                {job.recipientDisplayName ?? "-"}
                {job.recipientPhoneMasked ? ` · ${job.recipientPhoneMasked}` : ""}
              </p>
              <p className="font-mono-tight text-xs text-muted-foreground">{tsOf(format, ts)}</p>
              <p className="tabular-nums text-xs">
                {t("notificationHistory.attemptsLine", { attempts: job.attemptCount, max: job.maxAttempts })}
              </p>
              {job.errorDetail ? (
                <p className="break-words text-xs text-muted-foreground">
                  {job.errorDetail.slice(0, 120)}
                  {job.errorDetail.length > 120 ? "…" : ""}
                </p>
              ) : null}
              {job.traceId ? (
                <p className="font-mono-tight flex items-center gap-1 text-xs">
                  <span className="truncate">{job.traceId.slice(0, 12)}…</span>
                  <button type="button" className="text-primary" onClick={() => copyText(job.traceId ?? "", t)}>
                    <CopyIcon className="size-3" />
                  </button>
                </p>
              ) : null}
              {job.attempts.length > 0 ? (
                <Collapsible open={isAttOpen} onOpenChange={() => toggleAttempts(job.id)}>
                  <CollapsibleTrigger asChild>
                    <Button variant="ghost" size="sm" className="w-full justify-between">
                      {t("notificationHistory.attemptsCount", { count: job.attempts.length })}{" "}
                      {isAttOpen ? <ChevronUpIcon className="size-4" /> : <ChevronDownIcon className="size-4" />}
                    </Button>
                  </CollapsibleTrigger>
                  <CollapsibleContent className="pt-2">
                    <AttemptTable attempts={job.attempts} />
                  </CollapsibleContent>
                </Collapsible>
              ) : null}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function AttemptTable({ attempts }: { attempts: NormalizedAttempt[] }) {
  const t = useTranslations("alerts");
  const tc = useTranslations("common");
  const format = useFormatter();
  const [expandedDetail, setExpandedDetail] = useState<Set<string>>(new Set());
  function toggle(rowKey: string) {
    setExpandedDetail((cur) => {
      const next = new Set(cur);
      if (next.has(rowKey)) next.delete(rowKey);
      else next.add(rowKey);
      return next;
    });
  }
  return (
    <div className="overflow-hidden rounded-md border">
      <table className="w-full text-xs">
        <thead>
          <tr className="bg-muted/50 text-left">
            <th className="px-3 py-1.5 font-medium">#</th>
            <th className="px-3 py-1.5 font-medium">{tc("status")}</th>
            <th className="px-3 py-1.5 font-medium">{t("notificationHistory.headerAt")}</th>
            <th className="px-3 py-1.5 font-medium">{t("notificationHistory.headerDetail")}</th>
            <th className="px-3 py-1.5 font-medium">{t("notificationHistory.headerTrace")}</th>
          </tr>
        </thead>
        <tbody>
          {attempts.map((a, index) => {
            const rowKey = `${a.attemptNumber}-${index}`;
            const isOpen = expandedDetail.has(rowKey);
            return (
              <tr key={rowKey} className="border-t">
                <td className="px-3 py-1.5 tabular-nums">{a.attemptNumber}</td>
                <td className="px-3 py-1.5">
                  <Badge variant="outline" className="text-xs">
                    {a.status}
                  </Badge>
                </td>
                <td className="font-mono-tight whitespace-nowrap px-3 py-1.5">{timeOf(format, a.attemptedAt)}</td>
                <td className="max-w-48 break-words px-3 py-1.5">
                  {a.responseDetail ? (
                    <span title={a.responseDetail}>
                      {isOpen ? a.responseDetail.slice(0, 512) : truncate120(a.responseDetail)}
                      {a.responseDetail.length > 120 ? (
                        <button type="button" className="ml-1 text-primary underline" onClick={() => toggle(rowKey)}>
                          {isOpen ? t("notificationHistory.less") : t("notificationHistory.more")}
                        </button>
                      ) : null}
                    </span>
                  ) : (
                    "—"
                  )}
                </td>
                <td className="font-mono-tight px-3 py-1.5">
                  {a.traceId ? (
                    <span className="inline-flex items-center gap-1">
                      {a.traceId.slice(0, 8)}…
                      <button
                        type="button"
                        aria-label={t("notificationHistory.copyTraceAria")}
                        onClick={() => copyText(a.traceId ?? "", t)}
                      >
                        <CopyIcon className="size-3" />
                      </button>
                    </span>
                  ) : (
                    "—"
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Audit Evidence Section
// ---------------------------------------------------------------------------

function AuditEvidenceSection({
  entries,
  isLoading,
  error,
  onRetry,
}: {
  entries: AuditLogEntryView[] | undefined;
  isLoading: boolean;
  error: unknown | null;
  onRetry?: () => void;
}) {
  const t = useTranslations("alerts");
  const tc = useTranslations("common");
  const te = useTranslations("errors");
  const format = useFormatter();
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  function toggle(id: string) {
    setExpanded((cur) => {
      const next = new Set(cur);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  if (isLoading) {
    return (
      <div className="space-y-2 p-4">
        <div className="h-4 w-36 animate-pulse rounded bg-muted" />
        <div className="h-20 w-full animate-pulse rounded bg-muted" />
      </div>
    );
  }
  if (error) {
    const traceId = extractTraceId(error);
    const message = extractErrorMessage(error, te);
    return (
      <div className="space-y-2 rounded-md border border-destructive/30 p-4">
        <p className="text-sm font-medium text-destructive">{t("notificationHistory.auditLoadFailed")}</p>
        <p className="text-xs text-muted-foreground">{message}</p>
        {traceId ? (
          <p className="font-mono-tight text-xs text-muted-foreground">
            {t("notificationHistory.traceIdLine", { traceId })}
          </p>
        ) : null}
        {onRetry ? (
          <Button variant="outline" size="sm" onClick={onRetry}>
            {tc("retry")}
          </Button>
        ) : null}
      </div>
    );
  }
  if (!entries || entries.length === 0) {
    return (
      <div className="p-4 text-center">
        <p className="text-sm text-muted-foreground">{t("notificationHistory.emptyAudit")}</p>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      {/* Desktop table */}
      <div className="hidden overflow-hidden rounded-md border md:block">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("notificationHistory.headerTimestamp")}</TableHead>
              <TableHead>{t("notificationHistory.headerActor")}</TableHead>
              <TableHead>{t("notificationHistory.headerAction")}</TableHead>
              <TableHead>{t("notificationHistory.headerResult")}</TableHead>
              <TableHead className="w-10" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {entries.map((entry) => {
              const id = entry.id ?? `${entry.createdAt}-${entry.action}-${entry.entityId}`;
              const isOpen = expanded.has(id);
              return (
                <TableRow key={id}>
                  <TableCell className="font-mono-tight whitespace-nowrap text-xs">
                    {tsOf(format, entry.createdAt)}
                  </TableCell>
                  <TableCell className="max-w-32 truncate" title={entry.actorName ?? undefined}>
                    {entry.actorName ?? "-"}
                  </TableCell>
                  <TableCell>
                    <Badge variant="outline">{entry.action}</Badge>
                  </TableCell>
                  <TableCell className="max-w-64 truncate" title={entry.entityLabel ?? undefined}>
                    {entry.entityLabel ?? entry.entityType ?? "-"}
                  </TableCell>
                  <TableCell>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="size-8 p-0"
                      onClick={() => toggle(id)}
                      aria-label={t("notificationHistory.toggleChangeAria")}
                    >
                      {isOpen ? <ChevronUpIcon className="size-4" /> : <ChevronDownIcon className="size-4" />}
                    </Button>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
        {entries.map((entry) => {
          const id = entry.id ?? `${entry.createdAt}-${entry.action}-${entry.entityId}`;
          const isOpen = expanded.has(id);
          return isOpen ? (
            <div key={`${id}-diff`} className="border-t p-3">
              <ValueDiff entry={entry} />
            </div>
          ) : null;
        })}
      </div>

      {/* Mobile stacked */}
      <div className="space-y-2 md:hidden">
        {entries.map((entry) => {
          const id = entry.id ?? `${entry.createdAt}-${entry.action}-${entry.entityId}`;
          const isOpen = expanded.has(id);
          return (
            <div key={id} className="space-y-1.5 rounded-lg border p-3">
              <div className="flex items-center justify-between gap-2">
                <Badge variant="outline">{entry.action}</Badge>
                <span className="font-mono-tight text-xs text-muted-foreground">{timeOf(format, entry.createdAt)}</span>
              </div>
              <p className="text-sm">
                {entry.actorName ?? "-"} · {entry.entityType}
              </p>
              <p className="truncate text-xs text-muted-foreground" title={entry.entityLabel ?? undefined}>
                {entry.entityLabel ?? "-"}
              </p>
              <Button variant="ghost" size="sm" className="w-full justify-between" onClick={() => toggle(id)}>
                {t("notificationHistory.headerDetail")}{" "}
                {isOpen ? <ChevronUpIcon className="size-4" /> : <ChevronDownIcon className="size-4" />}
              </Button>
              {isOpen ? <ValueDiff entry={entry} /> : null}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function ValueDiff({ entry }: { entry: AuditLogEntryView }) {
  const t = useTranslations("alerts");
  const previous = (entry.previousValue as Record<string, unknown> | null) ?? {};
  const next = (entry.newValue as Record<string, unknown> | null) ?? {};
  const keys = [...new Set([...Object.keys(previous), ...Object.keys(next)])].sort();
  const hasChanges = keys.length > 0 && (Object.keys(previous).length > 0 || Object.keys(next).length > 0);
  if (!hasChanges) {
    return (
      <p className="py-1 text-sm text-muted-foreground">
        {entry.action === "CREATE"
          ? t("notificationHistory.recordCreated")
          : entry.action === "DELETE"
            ? t("notificationHistory.recordDeleted")
            : t("notificationHistory.noFieldChanges")}
      </p>
    );
  }
  return (
    <div className="overflow-hidden rounded-md border">
      <table className="w-full text-sm">
        <thead>
          <tr className="bg-muted/50 text-left">
            <th className="px-3 py-1.5 font-medium">{t("notificationHistory.headerField")}</th>
            <th className="px-3 py-1.5 font-medium">{t("notificationHistory.headerBefore")}</th>
            <th className="px-3 py-1.5 font-medium">{t("notificationHistory.headerAfter")}</th>
          </tr>
        </thead>
        <tbody>
          {keys.map((key) => (
            <tr key={key} className="border-t">
              <td className="px-3 py-1.5 align-top font-medium">{key}</td>
              <td className="break-words px-3 py-1.5 align-top text-muted-foreground">
                <ValueCell value={previous[key]} />
              </td>
              <td className="break-words px-3 py-1.5 align-top">
                <ValueCell value={next[key]} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ValueCell({ value }: { value: unknown }) {
  if (value === null || value === undefined || value === "") return <span className="text-muted-foreground">—</span>;
  if (typeof value === "boolean") return <span>{value ? "true" : "false"}</span>;
  if (typeof value === "object") return <span className="break-words">{JSON.stringify(value)}</span>;
  return <span className="break-words">{String(value)}</span>;
}
