"use client";

import { useState } from "react";

import { ChevronDownIcon, ChevronUpIcon } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTableSortHeader } from "@/components/ui/data-table-sort-header";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { AuditLogEntryView } from "@/lib/api/generated/model";
import { cn } from "@/lib/utils";

export function AuditLogTable({
  entries,
  plantNameById,
  sort,
  onSortChange,
}: {
  readonly entries: AuditLogEntryView[];
  readonly plantNameById: Record<string, string>;
  readonly sort: string;
  readonly onSortChange: (sort: string) => void;
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

  return (
    <div>
      <div className="hidden md:block">
        <AuditLogTableDesktop
          entries={entries}
          plantNameById={plantNameById}
          expanded={expanded}
          onToggle={toggle}
          sort={sort}
          onSortChange={onSortChange}
        />
      </div>
      <div className="md:hidden">
        <AuditLogCards entries={entries} plantNameById={plantNameById} expanded={expanded} onToggle={toggle} />
      </div>
    </div>
  );
}

function AuditLogTableDesktop({
  entries,
  plantNameById,
  expanded,
  onToggle,
  sort,
  onSortChange,
}: {
  readonly entries: AuditLogEntryView[];
  readonly plantNameById: Record<string, string>;
  readonly expanded: ReadonlySet<string>;
  readonly onToggle: (id: string) => void;
  readonly sort: string;
  readonly onSortChange: (sort: string) => void;
}) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>
            <DataTableSortHeader title="Timestamp" field="createdAt" sort={sort} onSortChange={onSortChange} />
          </TableHead>
          <TableHead>
            <DataTableSortHeader title="Actor" field="actorName" sort={sort} onSortChange={onSortChange} />
          </TableHead>
          <TableHead>
            <DataTableSortHeader title="Action" field="action" sort={sort} onSortChange={onSortChange} />
          </TableHead>
          <TableHead>
            <DataTableSortHeader title="Entity" field="entityType" sort={sort} onSortChange={onSortChange} />
          </TableHead>
          <TableHead>Record</TableHead>
          <TableHead>Plant</TableHead>
          <TableHead className="w-10" />
        </TableRow>
      </TableHeader>
      <TableBody>
        {entries.map((entry) => {
          const id = entry.id ?? "";
          const isOpen = expanded.has(id);
          return <EntryRows key={id} entry={entry} plantNameById={plantNameById} isOpen={isOpen} onToggle={onToggle} />;
        })}
      </TableBody>
    </Table>
  );
}

function EntryRows({
  entry,
  plantNameById,
  isOpen,
  onToggle,
}: {
  readonly entry: AuditLogEntryView;
  readonly plantNameById: Record<string, string>;
  readonly isOpen: boolean;
  readonly onToggle: (id: string) => void;
}) {
  const id = entry.id ?? "";
  return (
    <>
      <TableRow>
        <TableCell className="whitespace-nowrap font-mono text-xs text-muted-foreground">
          {formatDateTime(entry.createdAt)}
        </TableCell>
        <TableCell className="max-w-56">
          <span className="truncate font-medium" title={entry.actorName}>
            {entry.actorName ?? "-"}
          </span>
        </TableCell>
        <TableCell>
          <ActionBadge action={entry.action} />
        </TableCell>
        <TableCell>
          <span className="font-medium">{entityTypeLabel(entry.entityType)}</span>
        </TableCell>
        <TableCell className="max-w-72">
          <span className="truncate" title={entry.entityLabel}>
            {entry.entityLabel ?? "-"}
          </span>
        </TableCell>
        <TableCell>{plantLabel(entry.plantId, plantNameById)}</TableCell>
        <TableCell className="text-right">
          <Button
            variant="ghost"
            size="sm"
            className="size-8 p-0"
            onClick={() => onToggle(id)}
            aria-label="Toggle change detail"
          >
            {isOpen ? <ChevronUpIcon className="size-4" /> : <ChevronDownIcon className="size-4" />}
          </Button>
        </TableCell>
      </TableRow>
      {isOpen ? (
        <TableRow className="hover:bg-transparent">
          <TableCell colSpan={7}>
            <ValueDiff entry={entry} />
          </TableCell>
        </TableRow>
      ) : null}
    </>
  );
}

function AuditLogCards({
  entries,
  plantNameById,
  expanded,
  onToggle,
}: {
  readonly entries: AuditLogEntryView[];
  readonly plantNameById: Record<string, string>;
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
              const id = entry.id ?? "";
              const isOpen = expanded.has(id);
              return (
                <div key={id} className="rounded-lg border p-3">
                  <div className="flex items-start justify-between gap-3">
                    <div className="flex min-w-0 flex-col gap-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <ActionBadge action={entry.action} />
                        <span className="font-medium">{entityTypeLabel(entry.entityType)}</span>
                      </div>
                      <p className="truncate text-sm" title={entry.entityLabel}>
                        {entry.entityLabel ?? "-"}
                      </p>
                      <p className="text-muted-foreground text-xs">
                        {entry.actorName ?? "-"} · {plantLabel(entry.plantId, plantNameById)}
                      </p>
                      <p className="font-mono text-muted-foreground text-xs">{formatTime(entry.createdAt)}</p>
                    </div>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="size-8 shrink-0 p-0"
                      onClick={() => onToggle(id)}
                      aria-label="Toggle change detail"
                    >
                      {isOpen ? <ChevronUpIcon className="size-4" /> : <ChevronDownIcon className="size-4" />}
                    </Button>
                  </div>
                  {isOpen ? (
                    <div className="mt-3">
                      <ValueDiff entry={entry} />
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

function ActionBadge({ action }: { readonly action: AuditLogEntryView["action"] }) {
  if (action === "CREATE") {
    return <Badge variant="default">CREATE</Badge>;
  }
  if (action === "DELETE") {
    return <Badge variant="destructive">DELETE</Badge>;
  }
  return <Badge variant="outline">UPDATE</Badge>;
}

function ValueDiff({ entry }: { readonly entry: AuditLogEntryView }) {
  const previous = entry.previousValue ?? {};
  const next = entry.newValue ?? {};
  const keys = [...new Set([...Object.keys(previous), ...Object.keys(next)])].sort();
  const hasChanges = keys.length > 0 && (Object.keys(previous).length > 0 || Object.keys(next).length > 0);

  if (!hasChanges) {
    return (
      <p className="text-muted-foreground py-1 text-sm">
        {entry.action === "DELETE"
          ? "Record deleted."
          : entry.action === "CREATE"
            ? "Record created."
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
              <td className="px-3 py-1.5 align-top font-medium">{key}</td>
              <td className="px-3 py-1.5 align-top text-muted-foreground">
                <ValueCell value={previous[key]} />
              </td>
              <td
                className={cn(
                  "px-3 py-1.5 align-top",
                  hasChanged(key, previous, next) ? "font-medium" : "text-muted-foreground",
                )}
              >
                <ValueCell value={next[key]} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ValueCell({ value }: { readonly value: unknown }) {
  if (value === null || value === undefined || value === "") {
    return <span className="text-muted-foreground">—</span>;
  }
  if (typeof value === "boolean") {
    return <span>{value ? "true" : "false"}</span>;
  }
  return <span className="break-words">{String(value)}</span>;
}

function hasChanged(key: string, previous: Record<string, unknown>, next: Record<string, unknown>) {
  return JSON.stringify(previous[key] ?? null) !== JSON.stringify(next[key] ?? null);
}

function plantLabel(plantId: string | undefined, plantNameById: Record<string, string>) {
  if (!plantId) {
    return <Badge variant="secondary">Global</Badge>;
  }
  const name = plantNameById[plantId];
  return name ? <span className="text-sm">{name}</span> : <span className="text-muted-foreground text-sm">Plant</span>;
}

function entityTypeLabel(entityType: AuditLogEntryView["entityType"]) {
  switch (entityType) {
    case "PLANT":
      return "Plant";
    case "MACHINE_GROUP":
      return "Machine group";
    case "MACHINE":
      return "Machine";
    case "SPAREPART_TAXONOMY":
      return "Sparepart taxonomy";
    case "SPAREPART":
      return "Sparepart";
    case "INSTALLATION":
      return "Installation";
    case "RESPONSIBILITY":
      return "Responsibility";
    default:
      return entityType ?? "-";
  }
}

function groupByDate(entries: AuditLogEntryView[]) {
  const groups = new Map<string, AuditLogEntryView[]>();
  for (const entry of entries) {
    const date = (entry.createdAt ?? "").slice(0, 10);
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
  if (!value) {
    return "-";
  }
  return new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}

function formatDateOnly(date: string) {
  if (!date) {
    return "Unknown date";
  }
  return new Intl.DateTimeFormat("en", { dateStyle: "medium" }).format(new Date(`${date}T00:00:00Z`));
}

function formatTime(value: string | undefined) {
  if (!value) {
    return "-";
  }
  return new Intl.DateTimeFormat("en", { timeStyle: "short" }).format(new Date(value));
}
