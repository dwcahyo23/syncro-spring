"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { useQuery } from "@tanstack/react-query";
import { type ColumnDef, flexRender, getCoreRowModel, useReactTable } from "@tanstack/react-table";
import { format } from "date-fns";
import { RefreshCwIcon, SearchIcon, TriangleAlertIcon } from "lucide-react";

import { MonthPicker } from "@/components/month-picker";
import { WorkorderActionsCell } from "@/features/workorders/components/workorder-actions-cell";
import { syncroFetch } from "@/lib/api/orval-mutator";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTablePagination } from "@/components/ui/data-table-pagination";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useWorkorders } from "@/features/workorders/hooks/use-workorders";
import type { WorkOrderListParams, WorkOrderListRow } from "@/features/workorders/types";

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

interface CategoryOption {
  code: string;
  label: string;
}

const PAGE_SIZE = 20;

/** Local-date ISO string (yyyy-MM-dd) — never shifts the day across timezones. */
function isoDate(d: Date): string {
  const year = d.getFullYear();
  const month = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

/** First day of the month containing {@code d}. */
function startOfMonth(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), 1);
}

/** Last day of the month containing {@code d}. */
function endOfMonth(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth() + 1, 0);
}

/**
 * Workorder table tab (workorder-table story): a server-paginated TanStack Table with a
 * month quick picker (prev/next arrows, no calendar date clicking), status/category/search
 * filters and prev/next pagination. The month defaults to the current month and maps to
 * {@code from}/{@code to} month boundaries. Loading/empty/error states are handled to spec.
 */
export function WorkorderTable() {
  const [month, setMonth] = useState(() => {
    const d = new Date();
    return { month: d.getMonth(), year: d.getFullYear() };
  });
  const [dateFrom, setDateFrom] = useState(() => isoDate(startOfMonth(new Date())));
  const [dateTo, setDateTo] = useState(() => isoDate(endOfMonth(new Date())));
  const [status, setStatus] = useState("");
  const [search, setSearch] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [page, setPage] = useState(0);
  const [categoryCode, setCategoryCode] = useState("");

  const { data: categoriesRes } = useQuery<CategoryOption[]>({
    queryKey: ["/api/v1/work-order-categories"],
    queryFn: async () => {
      const res = await syncroFetch<{ data: CategoryOption[] }>("/api/v1/work-order-categories", { method: "GET" });
      return res.data;
    },
    staleTime: 60_000,
  });

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
  const handleMonthChange = useCallback((next: { month: number; year: number }) => {
    setMonth(next);
    setDateFrom(isoDate(startOfMonth(new Date(next.year, next.month, 1))));
    setDateTo(isoDate(endOfMonth(new Date(next.year, next.month, 1))));
    setPage(0);
  }, []);
  const handleStatusChange = useCallback((value: string) => {
    setStatus(value === "all-statuses" ? "" : value);
    setPage(0);
  }, []);
  const handleCategoryChange = useCallback((value: string) => {
    setCategoryCode(value === "all-categories" ? "" : value);
    setPage(0);
  }, []);

  const params: WorkOrderListParams = {
    from: dateFrom || undefined,
    to: dateTo || undefined,
    status: status || undefined,
    categoryCode: categoryCode || undefined,
    search: debouncedSearch || undefined,
    page,
    size: PAGE_SIZE,
  };

  const { data, isLoading, isError, refetch } = useWorkorders(params);
  const pageCount = data ? Math.ceil(data.total / PAGE_SIZE) : 0;

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
        id: "category",
        header: "Category",
        cell: ({ row }) => (
          <span className="text-xs">
            {row.original.categoryCode ? `${row.original.categoryCode} · ${row.original.categoryLabel ?? ""}` : "-"}
          </span>
        ),
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
      {
        id: "actions",
        header: "Actions",
        cell: ({ row }) => <WorkorderActionsCell workOrderId={row.original.id} />,
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
          <span className="font-medium text-muted-foreground text-xs">Month</span>
          <MonthPicker value={month} onChange={handleMonthChange} />
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
          <span className="font-medium text-muted-foreground text-xs">Category</span>
          <Select value={categoryCode || "all-categories"} onValueChange={handleCategoryChange}>
            <SelectTrigger className="w-44" aria-label="Category">
              <SelectValue placeholder="All categories" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all-categories">All categories</SelectItem>
              {(categoriesRes ?? []).map((category) => (
                <SelectItem key={category.code} value={category.code}>
                  {category.code} · {category.label}
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

/** Renders a workorder status as a coloured badge (Open = red, in-flight = amber, procurement = blue, done = green). */
function StatusBadge({ status }: { status: string }) {
  return <Badge className={statusClass(status)}>{status}</Badge>;
}

function statusClass(status: string): string {
  switch (status) {
    case "OPEN":
      return "status-badge-critical";
    case "ASSIGNED":
    case "IN_PROGRESS":
      return "status-badge-warning";
    case "ON_PROCUREMENT":
      return "status-badge-info";
    case "DONE":
    case "CLOSED":
      return "status-badge-healthy";
    case "CANCELLED":
      return "status-badge-neutral";
    default:
      return "status-badge-neutral";
  }
}

function formatDate(iso: string) {
  try {
    return format(new Date(iso), "d MMM yyyy");
  } catch {
    return iso;
  }
}
