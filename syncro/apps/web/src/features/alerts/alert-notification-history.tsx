"use client";

import { useState } from "react";

import { ChevronDownIcon, ChevronUpIcon, CopyIcon } from "lucide-react";

import { type EscalationStep, EscalationTimeline } from "@/components/syncro/escalation-timeline";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { AuditLogEntryView } from "@/lib/api/generated/model";

// ---------------------------------------------------------------------------
// DTO types — mirrored from backend. Keep names identical so that Orval
// generation is diff-free when backend is reachable.
// ---------------------------------------------------------------------------

export interface NotificationAttemptView {
  attemptNumber: number;
  status: string;
  attemptedAt: string;
  responseDetail: string | null;
  traceId: string | null;
}

export interface NotificationJobView {
  id: string;
  alertId: string;
  escalationLevel: string;
  status: string; // NotificationJobStatus enum string
  recipientUserId: string | null;
  recipientDisplayName: string | null;
  recipientPhoneMasked: string | null;
  attemptCount: number;
  maxAttempts: number;
  sentAt: string | null;
  createdAt: string;
  updatedAt: string;
  nextAttemptAt?: string | null;
  errorDetail: string | null;
  traceId: string | null;
  attempts: NotificationAttemptView[];
}

export interface AlertNotificationHistoryResponse {
  items: NotificationJobView[];
  total: number;
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
  readonly alertStatus?: "OPEN" | "ACKNOWLEDGED" | "RESOLVED";
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function formatTs(iso?: string | null) {
  if (!iso) return "-";
  try {
    return new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short" }).format(new Date(iso));
  } catch {
    return iso;
  }
}

function formatTimeOnly(iso?: string | null) {
  if (!iso) return "-";
  try {
    return new Intl.DateTimeFormat("en", { timeStyle: "short" }).format(new Date(iso));
  } catch {
    return iso;
  }
}

function truncate120(value: string | null | undefined) {
  if (!value) return null;
  return value.length > 120 ? `${value.slice(0, 120)}…` : value;
}

async function copyText(value: string) {
  try {
    await navigator.clipboard.writeText(value);
  } catch {
    // ignore
  }
}

// ---------------------------------------------------------------------------
// Main composition
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
}: AlertNotificationHistoryProps) {
  // Map jobs to EscalationStep for timeline (ordered already by backend escalation order)
  const steps: EscalationStep[] = (history?.items ?? []).map((job) => ({
    level: job.escalationLevel,
    recipientDisplayName: job.recipientDisplayName,
    recipientPhoneMasked: job.recipientPhoneMasked,
    status: mapStatus(job.status),
    rawStatus: job.status,
    timestamp: job.sentAt ?? job.updatedAt ?? job.createdAt,
    nextSendAt: job.nextAttemptAt ?? undefined,
    deliveryResult: job.errorDetail?.slice(0, 512) ?? undefined,
    traceId: job.traceId ?? undefined,
  }));

  return (
    <div className="space-y-6">
      {/* Escalation Timeline */}
      <TimelineSection
        steps={steps}
        alertStatus={alertStatus}
        isLoading={isLoadingHistory}
        error={errorHistory}
        isEmpty={history ? history.items.length === 0 : false}
        onRetry={onRetryHistory}
      />

      {/* Notification history table */}
      <HistoryTableSection
        items={history?.items}
        isLoading={isLoadingHistory}
        error={errorHistory}
        onRetry={onRetryHistory}
      />

      {/* Audit evidence */}
      <AuditEvidenceSection
        entries={auditEntries}
        isLoading={isLoadingAudit}
        error={errorAudit}
        onRetry={onRetryAudit}
      />
    </div>
  );
}

function mapStatus(raw: string): EscalationStep["status"] {
  switch (raw) {
    case "SENT":
      return "sent";
    case "ESCALATED":
      return "sent";
    case "PENDING":
      return "pending";
    case "ROUTING_FAILED":
      return "failed";
    case "EXHAUSTED":
      return "failed";
    case "CANCELLED":
      return "stopped";
    case "RATE_LIMITED":
      return "rate-limited";
    default:
      // unknown/future per AC 2 -> pending/rate-limited neutral styling with raw label visible
      return "pending";
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
}: {
  steps: EscalationStep[];
  alertStatus: "OPEN" | "ACKNOWLEDGED" | "RESOLVED";
  isLoading: boolean;
  error: unknown | null;
  isEmpty: boolean;
  onRetry?: () => void;
}) {
  if (isLoading) {
    return (
      <div className="rounded-md border p-4 space-y-3">
        <div className="h-4 w-32 bg-muted animate-pulse rounded" />
        <div className="h-20 w-full bg-muted animate-pulse rounded" />
      </div>
    );
  }
  if (error) {
    return (
      <div className="rounded-md border border-destructive/30 p-4 space-y-2">
        <p className="text-sm font-medium text-destructive">Failed to load escalation timeline.</p>
        <p className="text-xs text-muted-foreground">Something went wrong. Please try again.</p>
        {onRetry ? (
          <Button variant="outline" size="sm" onClick={onRetry}>
            Retry
          </Button>
        ) : null}
      </div>
    );
  }
  if (isEmpty) {
    return (
      <div className="rounded-md border p-4 text-center">
        <p className="text-sm text-muted-foreground">
          No notifications queued for this alert yet. A TECHNICIAN job is created when the alert opens; escalation
          follows every 15 minutes.
        </p>
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
  items: NotificationJobView[] | undefined;
  isLoading: boolean;
  error: unknown | null;
  onRetry?: () => void;
}) {
  const [expandedJobs, setExpandedJobs] = useState<Set<string>>(new Set());
  const [expandedAttempts, setExpandedAttempts] = useState<Set<string>>(new Set());
  const [expandedErrors, setExpandedErrors] = useState<Set<string>>(new Set());

  function toggleJob(id: string) {
    setExpandedJobs((cur) => {
      const next = new Set(cur);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }
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
      <div className="rounded-md border p-4 space-y-2">
        <div className="h-4 w-48 bg-muted animate-pulse rounded" />
        <div className="h-32 w-full bg-muted animate-pulse rounded" />
      </div>
    );
  }
  if (error) {
    return (
      <div className="rounded-md border border-destructive/30 p-4 space-y-2">
        <p className="text-sm font-medium text-destructive">Failed to load notification history.</p>
        {onRetry ? (
          <Button variant="outline" size="sm" onClick={onRetry}>
            Retry
          </Button>
        ) : null}
      </div>
    );
  }
  if (!items || items.length === 0) {
    // Empty already handled by timeline; still show guidance if history empty but timeline not shown separately
    return null;
  }

  return (
    <div className="space-y-2">
      <h3 className="text-sm font-semibold">Notification History</h3>

      {/* Desktop table */}
      <div className="hidden md:block overflow-hidden rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Level</TableHead>
              <TableHead>Recipient</TableHead>
              <TableHead>Phone</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Attempts</TableHead>
              <TableHead>Time</TableHead>
              <TableHead>Detail</TableHead>
              <TableHead>Trace</TableHead>
              <TableHead className="w-10" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {items.map((job) => {
              const isOpen = expandedJobs.has(job.id);
              const isAttOpen = expandedAttempts.has(job.id);
              const isErrOpen = expandedErrors.has(job.id);
              const ts = job.sentAt ?? job.createdAt;
              return (
                <>
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
                    <TableCell className="font-mono-tight text-xs whitespace-nowrap">{formatTs(ts)}</TableCell>
                    <TableCell className="max-w-48">
                      {job.errorDetail ? (
                        <span className="text-xs break-words" title={job.errorDetail}>
                          {isErrOpen ? job.errorDetail.slice(0, 512) : truncate120(job.errorDetail)}
                          {job.errorDetail.length > 120 ? (
                            <button
                              type="button"
                              className="ml-1 text-primary underline"
                              onClick={() => toggleError(job.id)}
                            >
                              {isErrOpen ? "less" : "more"}
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
                          <span className="truncate max-w-20" title={job.traceId}>
                            {job.traceId.slice(0, 8)}…
                          </span>
                          <Button
                            variant="ghost"
                            size="icon"
                            className="size-6"
                            aria-label="Copy trace ID"
                            onClick={() => copyText(job.traceId!)}
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
                          aria-label="Toggle attempts"
                        >
                          {isAttOpen ? <ChevronUpIcon className="size-4" /> : <ChevronDownIcon className="size-4" />}
                        </Button>
                      ) : null}
                    </TableCell>
                  </TableRow>
                  {isAttOpen && job.attempts.length > 0 ? (
                    <TableRow className="hover:bg-transparent">
                      <TableCell colSpan={9}>
                        <AttemptTable attempts={job.attempts} />
                      </TableCell>
                    </TableRow>
                  ) : null}
                </>
              );
            })}
          </TableBody>
        </Table>
      </div>

      {/* Mobile stacked cards */}
      <div className="md:hidden space-y-2">
        {items.map((job) => {
          const isAttOpen = expandedAttempts.has(job.id);
          const ts = job.sentAt ?? job.createdAt;
          return (
            <div key={job.id} className="rounded-lg border p-3 space-y-1.5">
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
              <p className="text-xs text-muted-foreground font-mono-tight">{formatTs(ts)}</p>
              <p className="text-xs tabular-nums">
                Attempts: {job.attemptCount}/{job.maxAttempts}
              </p>
              {job.errorDetail ? (
                <p className="text-xs break-words text-muted-foreground">
                  {job.errorDetail.slice(0, 120)}
                  {job.errorDetail.length > 120 ? "…" : ""}
                </p>
              ) : null}
              {job.traceId ? (
                <p className="font-mono-tight text-xs flex items-center gap-1">
                  <span className="truncate">{job.traceId.slice(0, 12)}…</span>
                  <button type="button" className="text-primary" onClick={() => copyText(job.traceId!)}>
                    <CopyIcon className="size-3" />
                  </button>
                </p>
              ) : null}
              {job.attempts.length > 0 ? (
                <Collapsible open={isAttOpen} onOpenChange={() => toggleAttempts(job.id)}>
                  <CollapsibleTrigger asChild>
                    <Button variant="ghost" size="sm" className="w-full justify-between">
                      Attempts ({job.attempts.length}){" "}
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

function AttemptTable({ attempts }: { attempts: NotificationAttemptView[] }) {
  const [expandedDetail, setExpandedDetail] = useState<Set<number>>(new Set());
  function toggle(n: number) {
    setExpandedDetail((cur) => {
      const next = new Set(cur);
      if (next.has(n)) next.delete(n);
      else next.add(n);
      return next;
    });
  }
  return (
    <div className="overflow-hidden rounded-md border">
      <table className="w-full text-xs">
        <thead>
          <tr className="bg-muted/50 text-left">
            <th className="px-3 py-1.5 font-medium">#</th>
            <th className="px-3 py-1.5 font-medium">Status</th>
            <th className="px-3 py-1.5 font-medium">At</th>
            <th className="px-3 py-1.5 font-medium">Detail</th>
            <th className="px-3 py-1.5 font-medium">Trace</th>
          </tr>
        </thead>
        <tbody>
          {attempts.slice(0, 3).map((a) => {
            const isOpen = expandedDetail.has(a.attemptNumber);
            return (
              <tr key={a.attemptNumber} className="border-t">
                <td className="px-3 py-1.5 tabular-nums">{a.attemptNumber}</td>
                <td className="px-3 py-1.5">
                  <Badge variant="outline" className="text-xs">
                    {a.status}
                  </Badge>
                </td>
                <td className="px-3 py-1.5 font-mono-tight whitespace-nowrap">{formatTimeOnly(a.attemptedAt)}</td>
                <td className="px-3 py-1.5 max-w-48 break-words">
                  {a.responseDetail ? (
                    <span title={a.responseDetail}>
                      {isOpen ? a.responseDetail.slice(0, 512) : truncate120(a.responseDetail)}
                      {a.responseDetail.length > 120 ? (
                        <button
                          type="button"
                          className="ml-1 text-primary underline"
                          onClick={() => toggle(a.attemptNumber)}
                        >
                          {isOpen ? "less" : "more"}
                        </button>
                      ) : null}
                    </span>
                  ) : (
                    "—"
                  )}
                </td>
                <td className="px-3 py-1.5 font-mono-tight">
                  {a.traceId ? (
                    <span className="inline-flex items-center gap-1">
                      {a.traceId.slice(0, 8)}…
                      <button type="button" aria-label="Copy trace ID" onClick={() => copyText(a.traceId!)}>
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
      <div className="rounded-md border p-4 space-y-2">
        <div className="h-4 w-36 bg-muted animate-pulse rounded" />
        <div className="h-20 w-full bg-muted animate-pulse rounded" />
      </div>
    );
  }
  if (error) {
    return (
      <div className="rounded-md border border-destructive/30 p-4 space-y-2">
        <p className="text-sm font-medium text-destructive">Failed to load audit evidence.</p>
        {onRetry ? (
          <Button variant="outline" size="sm" onClick={onRetry}>
            Retry
          </Button>
        ) : null}
      </div>
    );
  }
  if (!entries || entries.length === 0) {
    return (
      <div className="space-y-2">
        <h3 className="text-sm font-semibold">Audit Evidence</h3>
        <div className="rounded-md border p-4 text-center">
          <p className="text-sm text-muted-foreground">No audit evidence yet.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      <h3 className="text-sm font-semibold">Audit Evidence</h3>
      {/* Desktop table */}
      <div className="hidden md:block overflow-hidden rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Timestamp</TableHead>
              <TableHead>Actor</TableHead>
              <TableHead>Action</TableHead>
              <TableHead>Result</TableHead>
              <TableHead className="w-10" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {entries.map((entry) => {
              const id = entry.id ?? `${entry.createdAt}-${entry.action}`;
              const isOpen = expanded.has(id);
              return (
                <>
                  <TableRow key={id}>
                    <TableCell className="font-mono-tight text-xs whitespace-nowrap">
                      {formatTs(entry.createdAt)}
                    </TableCell>
                    <TableCell className="max-w-32 truncate" title={entry.actorName}>
                      {entry.actorName ?? "-"}
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline">{entry.action}</Badge>
                    </TableCell>
                    <TableCell className="max-w-64 truncate" title={entry.entityLabel}>
                      {entry.entityLabel ?? entry.entityType ?? "-"}
                    </TableCell>
                    <TableCell>
                      <Button
                        variant="ghost"
                        size="sm"
                        className="size-8 p-0"
                        onClick={() => toggle(id)}
                        aria-label="Toggle change detail"
                      >
                        {isOpen ? <ChevronUpIcon className="size-4" /> : <ChevronDownIcon className="size-4" />}
                      </Button>
                    </TableCell>
                  </TableRow>
                  {isOpen ? (
                    <TableRow className="hover:bg-transparent">
                      <TableCell colSpan={5}>
                        <ValueDiff entry={entry} />
                      </TableCell>
                    </TableRow>
                  ) : null}
                </>
              );
            })}
          </TableBody>
        </Table>
      </div>

      {/* Mobile stacked */}
      <div className="md:hidden space-y-2">
        {entries.map((entry) => {
          const id = entry.id ?? `${entry.createdAt}-${entry.action}`;
          const isOpen = expanded.has(id);
          return (
            <div key={id} className="rounded-lg border p-3 space-y-1.5">
              <div className="flex items-center justify-between gap-2">
                <Badge variant="outline">{entry.action}</Badge>
                <span className="font-mono-tight text-xs text-muted-foreground">{formatTimeOnly(entry.createdAt)}</span>
              </div>
              <p className="text-sm">
                {entry.actorName ?? "-"} · {entry.entityType}
              </p>
              <p className="text-xs text-muted-foreground truncate" title={entry.entityLabel}>
                {entry.entityLabel ?? "-"}
              </p>
              <Button variant="ghost" size="sm" className="w-full justify-between" onClick={() => toggle(id)}>
                Detail {isOpen ? <ChevronUpIcon className="size-4" /> : <ChevronDownIcon className="size-4" />}
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
  const previous = (entry.previousValue as Record<string, unknown> | null) ?? {};
  const next = (entry.newValue as Record<string, unknown> | null) ?? {};
  const keys = [...new Set([...Object.keys(previous), ...Object.keys(next)])].sort();
  const hasChanges = keys.length > 0 && (Object.keys(previous).length > 0 || Object.keys(next).length > 0);
  if (!hasChanges) {
    return (
      <p className="text-sm text-muted-foreground py-1">
        {entry.action === "CREATE"
          ? "Record created."
          : entry.action === "DELETE"
            ? "Record deleted."
            : "No field changes recorded."}
      </p>
    );
  }
  return (
    <div className="overflow-hidden rounded-md border">
      <table className="w-full text-sm">
        <thead>
          <tr className="bg-muted/50 text-left">
            <th className="px-3 py-1.5 font-medium">Field</th>
            <th className="px-3 py-1.5 font-medium">Before</th>
            <th className="px-3 py-1.5 font-medium">After</th>
          </tr>
        </thead>
        <tbody>
          {keys.map((key) => (
            <tr key={key} className="border-t">
              <td className="px-3 py-1.5 font-medium align-top">{key}</td>
              <td className="px-3 py-1.5 text-muted-foreground align-top break-words">
                <ValueCell value={previous[key]} />
              </td>
              <td className="px-3 py-1.5 align-top break-words">
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
  return <span className="break-words">{String(value)}</span>;
}
