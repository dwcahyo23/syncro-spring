"use client";

import { useState } from "react";

import { ChevronDownIcon, ChevronUpIcon } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { QuarantineEntryView } from "@/features/system-health/hooks/use-quarantine-log";
import { cn } from "@/lib/utils";

// ─── Public API ───────────────────────────────────────────────────────────────

export function QuarantineLogTable({
  entries,
  isLoading,
  isError,
  page,
  totalPages,
  onPageChange,
}: {
  readonly entries: QuarantineEntryView[];
  readonly isLoading: boolean;
  readonly isError: boolean;
  readonly page: number;
  readonly totalPages: number;
  readonly onPageChange: (page: number) => void;
}) {
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  function toggle(id: string) {
    setExpanded((current) => {
      const next = new Set(current);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  if (isLoading) {
    return <QuarantineSkeleton />;
  }

  if (isError) {
    return (
      <div className="rounded-lg border border-destructive/40 bg-destructive/10 px-4 py-3 text-sm text-destructive">
        Failed to load quarantine log. Check your connection and try again.
      </div>
    );
  }

  if (entries.length === 0) {
    return (
      <div className="rounded-lg border border-dashed px-4 py-8 text-center text-sm text-muted-foreground">
        No quarantined messages found.
      </div>
    );
  }

  return (
    <div className="space-y-3">
      <div className="hidden md:block">
        <QuarantineTableDesktop entries={entries} expanded={expanded} onToggle={toggle} />
      </div>
      <div className="md:hidden">
        <QuarantineCards entries={entries} expanded={expanded} onToggle={toggle} />
      </div>
      {totalPages > 1 ? (
        <div className="flex items-center justify-between gap-2 pt-1 text-sm">
          <span className="text-muted-foreground">
            Page {page + 1} of {totalPages}
          </span>
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={page === 0}
              onClick={() => onPageChange(page - 1)}
            >
              Previous
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={page + 1 >= totalPages}
              onClick={() => onPageChange(page + 1)}
            >
              Next
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}

// ─── Desktop table ─────────────────────────────────────────────────────────────

function QuarantineTableDesktop({
  entries,
  expanded,
  onToggle,
}: {
  readonly entries: QuarantineEntryView[];
  readonly expanded: ReadonlySet<string>;
  readonly onToggle: (id: string) => void;
}) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead className="whitespace-nowrap">Received At</TableHead>
          <TableHead>Topic</TableHead>
          <TableHead>Reason</TableHead>
          <TableHead>Field</TableHead>
          <TableHead>Trace ID</TableHead>
          <TableHead className="w-8" />
        </TableRow>
      </TableHeader>
      <TableBody>
        {entries.map((entry) => (
          <EntryRows
            key={entry.id}
            entry={entry}
            isOpen={expanded.has(entry.id)}
            onToggle={onToggle}
          />
        ))}
      </TableBody>
    </Table>
  );
}

function EntryRows({
  entry,
  isOpen,
  onToggle,
}: {
  readonly entry: QuarantineEntryView;
  readonly isOpen: boolean;
  readonly onToggle: (id: string) => void;
}) {
  return (
    <>
      <TableRow>
        <TableCell className="whitespace-nowrap font-mono text-xs text-muted-foreground">
          {formatDateTime(entry.receivedAt)}
        </TableCell>
        <TableCell className="max-w-64">
          <span className="truncate font-mono text-xs" title={entry.topic}>
            {entry.topic}
          </span>
        </TableCell>
        <TableCell>
          <ReasonBadge reason={entry.rejectionReason} />
        </TableCell>
        <TableCell>
          <span className="font-mono text-xs text-muted-foreground">
            {entry.rejectionField ?? "-"}
          </span>
        </TableCell>
        <TableCell className="max-w-40">
          <span className="truncate font-mono text-xs text-muted-foreground" title={entry.traceId}>
            {entry.traceId}
          </span>
        </TableCell>
        <TableCell className="text-right">
          <Button
            variant="ghost"
            size="sm"
            className="size-8 p-0"
            onClick={() => onToggle(entry.id)}
            aria-label="Toggle payload preview"
          >
            {isOpen ? <ChevronUpIcon className="size-4" /> : <ChevronDownIcon className="size-4" />}
          </Button>
        </TableCell>
      </TableRow>
      {isOpen ? (
        <TableRow className="hover:bg-transparent">
          <TableCell colSpan={6}>
            <PayloadPreview payload={entry.rawPayload} />
          </TableCell>
        </TableRow>
      ) : null}
    </>
  );
}

// ─── Mobile cards ──────────────────────────────────────────────────────────────

function QuarantineCards({
  entries,
  expanded,
  onToggle,
}: {
  readonly entries: QuarantineEntryView[];
  readonly expanded: ReadonlySet<string>;
  readonly onToggle: (id: string) => void;
}) {
  const groups = groupByDate(entries);
  return (
    <div className="space-y-4">
      {groups.map(([date, groupEntries]) => (
        <div key={date} className="space-y-2">
          <h3 className="text-muted-foreground text-sm font-medium">{formatDateOnly(date)}</h3>
          <div className="space-y-2">
            {groupEntries.map((entry) => {
              const isOpen = expanded.has(entry.id);
              return (
                <div key={entry.id} className="rounded-lg border p-3">
                  <div className="flex items-start justify-between gap-3">
                    <div className="flex min-w-0 flex-col gap-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <ReasonBadge reason={entry.rejectionReason} />
                        {entry.rejectionField ? (
                          <span className="font-mono text-xs text-muted-foreground">
                            {entry.rejectionField}
                          </span>
                        ) : null}
                      </div>
                      <p className="truncate font-mono text-xs" title={entry.topic}>
                        {entry.topic}
                      </p>
                      <p className="font-mono text-muted-foreground text-xs">
                        {formatTime(entry.receivedAt)}
                      </p>
                    </div>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="size-8 shrink-0 p-0"
                      onClick={() => onToggle(entry.id)}
                      aria-label="Toggle payload preview"
                    >
                      {isOpen ? <ChevronUpIcon className="size-4" /> : <ChevronDownIcon className="size-4" />}
                    </Button>
                  </div>
                  {isOpen ? (
                    <div className="mt-3">
                      <PayloadPreview payload={entry.rawPayload} />
                    </div>
                  ) : null}
                </div>
              );
            })}
          </div>
        </div>
      ))}
    </div>
  );
}

// ─── Payload preview ───────────────────────────────────────────────────────────

function PayloadPreview({ payload }: { readonly payload: string }) {
  const display = payload.length > 500 ? `${payload.slice(0, 500)}…` : payload;
  return (
    <pre className="overflow-x-auto whitespace-pre-wrap break-all rounded bg-muted px-3 py-2 font-mono text-xs">
      {display || "(empty)"}
    </pre>
  );
}

// ─── Reason badge ──────────────────────────────────────────────────────────────

const REASON_VARIANT: Record<string, { label: string; className: string }> = {
  malformed_topic: { label: "Malformed Topic", className: "border-orange-500/40 bg-orange-500/10 text-orange-700 dark:text-orange-400" },
  inactive_machine: { label: "Inactive Machine", className: "border-yellow-500/40 bg-yellow-500/10 text-yellow-700 dark:text-yellow-400" },
  unparseable_payload: { label: "Unparseable", className: "border-red-500/40 bg-red-500/10 text-red-700 dark:text-red-400" },
  missing_contract_field: { label: "Missing Field", className: "border-purple-500/40 bg-purple-500/10 text-purple-700 dark:text-purple-400" },
  unsupported_schema_version: { label: "Bad Schema Version", className: "border-blue-500/40 bg-blue-500/10 text-blue-700 dark:text-blue-400" },
  machine_identity_mismatch: { label: "Identity Mismatch", className: "border-rose-500/40 bg-rose-500/10 text-rose-700 dark:text-rose-400" },
};

function ReasonBadge({ reason }: { readonly reason: string }) {
  const config = REASON_VARIANT[reason];
  if (config) {
    return (
      <Badge variant="outline" className={cn("text-xs", config.className)}>
        {config.label}
      </Badge>
    );
  }
  return (
    <Badge variant="outline" className="text-xs">
      {reason}
    </Badge>
  );
}

// ─── Skeleton ──────────────────────────────────────────────────────────────────

function QuarantineSkeleton() {
  return (
    <div className="space-y-2">
      {Array.from({ length: 5 }).map((_, i) => (
        <Skeleton key={i} className="h-10 w-full rounded" />
      ))}
    </div>
  );
}

// ─── Helpers ───────────────────────────────────────────────────────────────────

function groupByDate(entries: QuarantineEntryView[]): [string, QuarantineEntryView[]][] {
  const groups = new Map<string, QuarantineEntryView[]>();
  for (const entry of entries) {
    const date = (entry.receivedAt ?? "").slice(0, 10);
    const key = date || "unknown";
    const bucket = groups.get(key);
    if (bucket) {
      bucket.push(entry);
    } else {
      groups.set(key, [entry]);
    }
  }
  return [...groups.entries()];
}

function formatDateTime(value: string | undefined) {
  if (!value) return "-";
  return new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}

function formatDateOnly(date: string) {
  if (!date) return "Unknown date";
  return new Intl.DateTimeFormat("en", { dateStyle: "medium" }).format(new Date(`${date}T00:00:00Z`));
}

function formatTime(value: string | undefined) {
  if (!value) return "-";
  return new Intl.DateTimeFormat("en", { timeStyle: "short" }).format(new Date(value));
}
