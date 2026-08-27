"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { type ColumnDef, flexRender, getCoreRowModel, useReactTable } from "@tanstack/react-table";
import { format } from "date-fns";
import { RefreshCwIcon, SearchIcon, TriangleAlertIcon } from "lucide-react";

import { DateRangePicker } from "@/components/date-range-picker";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTablePagination } from "@/components/ui/data-table-pagination";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useWorkorders } from "@/features/workorders/hooks/use-workorders";
import type { WorkOrderListParams, WorkOrderListRow } from "@/features/workorders/types";
import { useListMachines } from "@/lib/api/generated/syncro";

const WORKORDER_STATUSES = [
  "DRAFT",
  "OPEN",
  "ASSIGNED",
  "IN_PROGRESS",
  "ON_PROCUREMENT",
  "DONE",
  "CLOSED",
  "CANCELLED",
] as const;

const PAGE_SIZE = 20;

/** Date-range quick presets (7d/30d/90d/This month) — each computes a from-date. */
const PRESETS: { label: string; from: (now: Date) => Date }[] = [
  {
    label: "7d",
    from: (now) => new Date(now.getFullYear(), now.getMonth(), now.getDate() - 6),
  },
  {
    label: "30d",
    from: (now) => new Date(now.getFullYear(), now.getMonth(), now.getDate() - 29),
  },
  {
    label: "90d",
    from: (now) => new Date(now.getFullYear(), now.getMonth(), now.getDate() - 89),
  },
  {
    label: "This month",
    from: (now) => new Date(now.getFullYear(), now.getMonth(), 1),
  },
];

/** Local-date ISO string (yyyy-MM-dd) — never shifts the day across timezones. */
function isoDate(d: Date): string {
  const year = d.getFullYear();
  const month = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

/**
 * Workorder table tab (workorder-table story): a server-paginated TanStack Table with a
 * month/date-range picker (This month default), status/machine/search filters and
 * prev/next pagination. Uses the existing {@link DateRangePicker} for the date range, a
 * shadcn Select for status, a shadcn Select for machine (reusing {@code useListMachines}),
 * and a 300ms debounced search box. Loading/empty/error states are handled to spec.
 */
export function WorkorderTable() {
  const [dateFrom, setDateFrom] = useState(() => {
    const d = new Date();
    return isoDate(new Date(d.getFullYear(), d.getMonth(), 1));
  });
  const [dateTo, setDateTo] = useState(() => isoDate(new Date()));
  const [status, setStatus] = useState("");
  const [machineId, setMachineId] = useState("");
  const [search, setSearch] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [page, setPage] = useState(0);

  const searchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const handleSearchChange = useCallback((value: string) => {
    setSearch(value);
    clearTimeout(searchTimerRef.current ?? undefined);
    searchTimerRef.current = setTimeout(() => {
      setDebouncedSearch(value);
      setPage(0);
    }, 300);
  }, []);

  useEffect(() => {
    return () => {
      clearTimeout(searchTimerRef.current ?? undefined);
    };
  }, []);

  // Reset page on any non-search filter change.
  const handleDateChange = useCallback((nextFrom: string, nextTo: string) => {
    setDateFrom(nextFrom);
    setDateTo(nextTo);
    setPage(0);
  }, []);
  const handleStatusChange = useCallback((value: string) => {
    setStatus(value === "all-statuses" ? "" : value);
    setPage(0);
  }, []);
  const handleMachineChange = useCallback((value: string) => {
    setMachineId(value === "all-machines" ? "" : value);
    setPage(0);
  }, []);

  const params: WorkOrderListParams = {
    from: dateFrom || undefined,
    to: dateTo || undefined,
    status: status || undefined,
    machineId: machineId || undefined,
    search: debouncedSearch || undefined,
    page,
    size: PAGE_SIZE,
  };

  const { data, isLoading, isError, refetch } = useWorkorders(params);
  const pageCount = data ? Math.ceil(data.total / PAGE_SIZE) : 0;

  const { data: machinesRes } = useListMachines({ page: 0, size: 200 });
  const machineItems = machinesRes?.data?.items ?? [];

  const columns = useMemo<ColumnDef<WorkOrderListRow>[]>(
    () => [
      {
        accessorKey: "id",
        header: "WO No",
        cell: ({ row }) => <span className="font-medium font-mono text-xs">{row.original.id}</span>,
      },
      {
        accessorKey: "status",
        header: "Status",
        cell: ({ row }) => <StatusBadge status={row.original.status} />,
      },
      {
        id: "machine",
        header: "Machine",
        cell: ({ row }) => {
          const item = row.original;
          const label = [item.machineCode, item.machineName].filter(Boolean).join(" · ");
          return <span className="text-xs">{label || "-"}</span>;
        },
      },
      {
        accessorKey: "description",
        header: "Problem",
        cell: ({ row }) => <span className="line-clamp-2 max-w-56 text-xs">{row.original.description || "-"}</span>,
      },
      {
        accessorKey: "plantCode",
        header: "Plant",
        cell: ({ row }) => <span className="text-xs">{row.original.plantCode || "-"}</span>,
      },
      {
        id: "technician",
        header: "Technician",
        cell: ({ row }) => <span className="text-xs">{row.original.assignedTechnicianName || "-"}</span>,
      },
      {
        accessorKey: "createdAt",
        header: "Created",
        cell: ({ row }) => <span className="text-muted-foreground text-xs">{formatDate(row.original.createdAt)}</span>,
      },
    ],
    [],
  );

  const table = useReactTable({
    data: data?.items ?? [],
    columns,
    pageCount,
    manualPagination: true,
    state: { pagination: { pageIndex: page, pageSize: PAGE_SIZE } },
    onPaginationChange: (updater) => {
      const next = typeof updater === "function" ? updater({ pageIndex: page, pageSize: PAGE_SIZE }) : updater;
      setPage(next.pageIndex);
    },
    getCoreRowModel: getCoreRowModel(),
  });

  return (
    <div className="space-y-4">
      {/* Filters */}
      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1">
          <span className="font-medium text-muted-foreground text-xs">Date range</span>
          <div className="flex flex-wrap items-center gap-2">
            <DateRangePicker
              value={{
                from: dateFrom ? new Date(`${dateFrom}T00:00:00`) : undefined,
                to: dateTo ? new Date(`${dateTo}T00:00:00`) : undefined,
              }}
              onChange={(range) => {
                if (!range?.from || !range?.to) {
                  return;
                }
                handleDateChange(isoDate(range.from), isoDate(range.to));
              }}
            />
            <div className="flex items-center gap-1">
              {PRESETS.map((preset) => (
                <Button
                  key={preset.label}
                  type="button"
                  variant="ghost"
                  size="sm"
                  className="h-7 px-2 text-xs"
                  onClick={() => handleDateChange(isoDate(preset.from(new Date())), isoDate(new Date()))}
                >
                  {preset.label}
                </Button>
              ))}
            </div>
          </div>
        </div>

        <div className="space-y-1">
          <span className="font-medium text-muted-foreground text-xs">Status</span>
          <Select value={status || "all-statuses"} onValueChange={handleStatusChange}>
            <SelectTrigger className="w-36" aria-label="Status">
              <SelectValue placeholder="All statuses" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all-statuses">All statuses</SelectItem>
              {WORKORDER_STATUSES.map((s) => (
                <SelectItem key={s} value={s}>
                  {s}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="space-y-1">
          <span className="font-medium text-muted-foreground text-xs">Machine</span>
          <Select value={machineId || "all-machines"} onValueChange={handleMachineChange}>
            <SelectTrigger className="w-52" aria-label="Machine">
              <SelectValue placeholder="All machines" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all-machines">All machines</SelectItem>
              {machineItems.map((machine) => (
                <SelectItem key={machine.id} value={machine.id ?? ""}>
                  {machine.code} · {machine.name} · {machine.plantCode}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="space-y-1">
          <span className="font-medium text-muted-foreground text-xs">Search</span>
          <div className="relative">
            <SearchIcon className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              value={search}
              onChange={(event) => handleSearchChange(event.target.value)}
              placeholder="Search WO, machine, category..."
              className="w-56 pl-8"
              aria-label="Search workorders"
            />
          </div>
        </div>

        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => void refetch()}
          aria-label="Refresh workorders"
        >
          <RefreshCwIcon className="size-4" />
        </Button>
      </div>

      {/* Loading skeleton */}
      {isLoading ? (
        <div className="space-y-2">
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-10 w-full" />
        </div>
      ) : null}

      {/* Error state */}
      {isError ? (
        <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed p-8 text-center">
          <TriangleAlertIcon className="size-8 text-muted-foreground" />
          <p className="text-muted-foreground text-sm">Failed to load workorders.</p>
          <Button type="button" variant="outline" size="sm" onClick={() => void refetch()}>
            Retry
          </Button>
        </div>
      ) : null}

      {/* Table */}
      {!isLoading && !isError && data && data.items.length > 0 ? (
        <div className="rounded-lg border">
          <Table>
            <TableHeader>
              {table.getHeaderGroups().map((headerGroup) => (
                <TableRow key={headerGroup.id}>
                  {headerGroup.headers.map((header) => (
                    <TableHead key={header.id}>
                      {header.isPlaceholder ? null : flexRender(header.column.columnDef.header, header.getContext())}
                    </TableHead>
                  ))}
                </TableRow>
              ))}
            </TableHeader>
            <TableBody>
              {table.getRowModel().rows.map((row) => (
                <TableRow key={row.id}>
                  {row.getVisibleCells().map((cell) => (
                    <TableCell key={cell.id}>{flexRender(cell.column.columnDef.cell, cell.getContext())}</TableCell>
                  ))}
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <DataTablePagination
            page={page}
            size={PAGE_SIZE}
            totalElements={data.total}
            onPageChange={setPage}
            onSizeChange={() => setPage(0)}
            pageSizeOptions={[PAGE_SIZE]}
          />
        </div>
      ) : null}

      {/* Empty state */}
      {!isLoading && !isError && data && data.items.length === 0 ? (
        <div className="flex flex-col items-center gap-2 rounded-lg border border-dashed p-8 text-center">
          <p className="text-muted-foreground text-sm">No workorders match this filter.</p>
        </div>
      ) : null}
    </div>
  );
}

/** Renders a workorder status as a coloured badge. */
function StatusBadge({ status }: { status: string }) {
  return <Badge variant={statusVariant(status)}>{status}</Badge>;
}

function statusVariant(status: string): "default" | "secondary" | "destructive" | "outline" | "ghost" | "link" {
  switch (status) {
    case "OPEN":
    case "ASSIGNED":
      return "default";
    case "IN_PROGRESS":
    case "ON_PROCUREMENT":
      return "secondary";
    case "DONE":
    case "CLOSED":
      return "outline";
    case "CANCELLED":
      return "destructive";
    default:
      return "ghost";
  }
}

function formatDate(iso: string) {
  try {
    return format(new Date(iso), "d MMM yyyy");
  } catch {
    return iso;
  }
}
