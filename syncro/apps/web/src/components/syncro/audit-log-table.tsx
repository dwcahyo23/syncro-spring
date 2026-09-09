"use client";

import { useState } from "react";

import { ChevronDownIcon, ChevronUpIcon } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";

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
  const t = useTranslations("auditLog.shared.table");
  const tc = useTranslations("common");
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>
            <DataTableSortHeader title={t("timestamp")} field="createdAt" sort={sort} onSortChange={onSortChange} />
          </TableHead>
          <TableHead>
            <DataTableSortHeader title={t("actor")} field="actorName" sort={sort} onSortChange={onSortChange} />
          </TableHead>
          <TableHead>
            <DataTableSortHeader title={t("action")} field="action" sort={sort} onSortChange={onSortChange} />
          </TableHead>
          <TableHead>
            <DataTableSortHeader title={t("entity")} field="entityType" sort={sort} onSortChange={onSortChange} />
          </TableHead>
          <TableHead>{t("record")}</TableHead>
          <TableHead>{tc("plant")}</TableHead>
          <TableHead>{t("decisionWord")}</TableHead>
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
  const t = useTranslations("auditLog.shared.table");
  const format = useFormatter();
  const id = entry.id ?? "";
  const formatDateTime = (value: string | undefined) => {
    if (!value) {
      return "-";
    }
    return format.dateTime(new Date(value), { dateStyle: "medium", timeStyle: "short" });
  };
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
          <span className="font-medium">{entityTypeLabel(entry.entityType, t)}</span>
        </TableCell>
        <TableCell className="max-w-72">
          <span className="truncate" title={entry.entityLabel}>
            {entry.entityLabel ?? "-"}
          </span>
        </TableCell>
        <TableCell>{plantLabel(entry.plantId, plantNameById, t)}</TableCell>
        <TableCell>
          <span className="font-mono text-xs text-muted-foreground" title={entry.decisionId ?? undefined}>
            {entry.decisionId ? shortenId(entry.decisionId) : "-"}
          </span>
        </TableCell>
        <TableCell className="text-right">
          <Button
            variant="ghost"
            size="sm"
            className="size-8 p-0"
            onClick={() => onToggle(id)}
            aria-label={t("toggleChange")}
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
  const t = useTranslations("auditLog.shared.table");
  const format = useFormatter();
  const groups = groupByDate(entries);
  const formatDateOnly = (date: string) => {
    if (!date) {
      return t("unknownDate");
    }
    return format.dateTime(new Date(`${date}T00:00:00Z`), { dateStyle: "medium" });
  };
  const formatTime = (value: string | undefined) => {
    if (!value) {
      return "-";
    }
    return format.dateTime(new Date(value), { timeStyle: "short" });
  };
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
                        <span className="font-medium">{entityTypeLabel(entry.entityType, t)}</span>
                      </div>
                      <p className="truncate text-sm" title={entry.entityLabel}>
                        {entry.entityLabel ?? "-"}
                      </p>
                      <p className="text-muted-foreground text-xs">
                        {entry.actorName ?? "-"} · {plantLabel(entry.plantId, plantNameById, t)}
                      </p>
                      {entry.decisionId ? (
                        <p className="font-mono text-muted-foreground text-xs">
                          {t("decisionLine", { id: shortenId(entry.decisionId) })}
                        </p>
                      ) : null}
                      <p className="font-mono text-muted-foreground text-xs">{formatTime(entry.createdAt)}</p>
                    </div>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="size-8 shrink-0 p-0"
                      onClick={() => onToggle(id)}
                      aria-label={t("toggleChange")}
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
  // Badge styling stays keyed on the raw code; the label is translated (fallback = raw code).
  const t = useTranslations("auditLog.shared.table");
  const label = t.has(`actionLabels.${action}`) ? t(`actionLabels.${action}`) : action;
  if (action === "CREATE") {
    return <Badge variant="default">{label}</Badge>;
  }
  if (action === "DELETE") {
    return <Badge variant="destructive">{label}</Badge>;
  }
  return <Badge variant="outline">{label}</Badge>;
}

function ValueDiff({ entry }: { readonly entry: AuditLogEntryView }) {
  const t = useTranslations("auditLog.shared.table");
  const previous = entry.previousValue ?? {};
  const next = entry.newValue ?? {};
  const keys = [...new Set([...Object.keys(previous), ...Object.keys(next)])].sort();
  const hasChanges = keys.length > 0 && (Object.keys(previous).length > 0 || Object.keys(next).length > 0);

  if (!hasChanges) {
    return (
      <p className="text-muted-foreground py-1 text-sm">
        {entry.action === "DELETE"
          ? t("recordDeleted")
          : entry.action === "CREATE"
            ? t("recordCreated")
            : t("noFieldChanges")}
      </p>
    );
  }

  return (
    <div className="overflow-hidden rounded-md border">
      <table className="w-full text-sm">
        <thead>
          <tr className="bg-muted/50 text-left">
            <th className="px-3 py-1.5 font-medium">{t("field")}</th>
            <th className="px-3 py-1.5 font-medium">{t("before")}</th>
            <th className="px-3 py-1.5 font-medium">{t("after")}</th>
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

function plantLabel(
  plantId: string | undefined,
  plantNameById: Record<string, string>,
  t: ReturnType<typeof useTranslations>,
) {
  if (!plantId) {
    return <Badge variant="secondary">{t("global")}</Badge>;
  }
  const name = plantNameById[plantId];
  return name ? (
    <span className="text-sm">{name}</span>
  ) : (
    <span className="text-muted-foreground text-sm">{t("plant")}</span>
  );
}

function entityTypeLabel(entityType: AuditLogEntryView["entityType"], t: ReturnType<typeof useTranslations>) {
  if (entityType && t.has(`entities.${entityType}`)) {
    return t(`entities.${entityType}`);
  }
  return entityType ?? "-";
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

function shortenId(id: string): string {
  return id.length > 8 ? id.slice(0, 8) + "…" : id;
}
