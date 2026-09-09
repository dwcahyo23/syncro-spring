"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { useQuery } from "@tanstack/react-query";
import { type ColumnDef, flexRender, getCoreRowModel, useReactTable } from "@tanstack/react-table";
import { RefreshCwIcon, SearchIcon, TriangleAlertIcon } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";

import { MonthPicker } from "@/components/month-picker";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTablePagination } from "@/components/ui/data-table-pagination";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useListUsersMaster } from "@/features/organization/hooks/use-users";
import { WorkorderActionsCell } from "@/features/workorders/components/workorder-actions-cell";
import { useWorkorders } from "@/features/workorders/hooks/use-workorders";
import type { WorkOrderListParams, WorkOrderListRow } from "@/features/workorders/types";
import { syncroFetch } from "@/lib/api/orval-mutator";

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

const PAGE_SIZE_OPTIONS = [10, 15, 20, 50, 100];
const DEFAULT_PAGE_SIZE = 20;

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
  const t = useTranslations("workOrders");
  const tc = useTranslations("common");
  const format = useFormatter();
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
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
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
    size: pageSize,
  };

  const { data, isLoading, isError, isFetching, refetch } = useWorkorders(params);
  const pageCount = data ? Math.ceil(data.total / pageSize) : 0;

  // User master is fetched ONCE per table (not per row) and shared with the
  // assign dialogs via props — the /auth/users cache is already shared app-wide.
  const { data: usersRes, isLoading: isLoadingUsers } = useListUsersMaster();
  const assignableUsers = (usersRes?.data ?? []).filter(
    (u) => u.applicationRole === "TECHNICIAN" || u.applicationRole === "STAFF_MAINTENANCE",
  );

  const handlePageSizeChange = useCallback((nextSize: number) => {
    setPageSize(nextSize);
    setPage(0);
  }, []);

  const formatDate = (iso: string) => {
    try {
      return format.dateTime(new Date(iso), { day: "numeric", month: "short", year: "numeric" });
    } catch {
      return iso;
    }
  };

  const columns = useMemo<ColumnDef<WorkOrderListRow>[]>(
    () => [
      {
        accessorKey: "id",
        header: t("table.woNo"),
        cell: ({ row }) => <span className="font-medium font-mono text-xs">{row.original.id}</span>,
      },
      {
        accessorKey: "status",
        header: tc("status"),
        cell: ({ row }) => <StatusBadge status={row.original.status} />,
      },
      {
        id: "machine",
        header: t("machine"),
        cell: ({ row }) => {
          const item = row.original;
          const label = [item.machineCode, item.machineName].filter(Boolean).join(" · ");
          return <span className="text-xs">{label || t("dash")}</span>;
        },
      },
      {
        id: "category",
        header: t("category"),
        cell: ({ row }) => (
          <span className="text-xs">
            {row.original.categoryCode
              ? `${row.original.categoryCode} · ${row.original.categoryLabel ?? ""}`
              : t("dash")}
          </span>
        ),
      },
      {
        accessorKey: "description",
        header: t("table.problem"),
        cell: ({ row }) => (
          <span className="line-clamp-2 max-w-56 text-xs">{row.original.description || t("dash")}</span>
        ),
      },
      {
        accessorKey: "plantCode",
        header: tc("plant"),
        cell: ({ row }) => <span className="text-xs">{row.original.plantCode || t("dash")}</span>,
      },
      {
        id: "technician",
        header: t("table.technician"),
        cell: ({ row }) => <span className="text-xs">{row.original.assignedTechnicianName || t("dash")}</span>,
      },
      {
        accessorKey: "createdAt",
        header: tc("createdAt"),
        cell: ({ row }) => <span className="text-muted-foreground text-xs">{formatDate(row.original.createdAt)}</span>,
      },
      {
        id: "actions",
        header: tc("actions"),
        cell: ({ row }) => (
          <WorkorderActionsCell
            workOrderId={row.original.id}
            status={row.original.status}
            assignedTechnicianName={row.original.assignedTechnicianName}
            assignableUsers={assignableUsers}
            isLoadingUsers={isLoadingUsers}
            createdAt={row.original.createdAt}
          />
        ),
      },
    ],
    // formatDate closes over the formatter; headers over the translator — both
    // stable per locale change, so include them to rebuild columns on switch.
    [t, tc, formatDate],
  );

  const table = useReactTable({
    data: data?.items ?? [],
    columns,
    pageCount,
    manualPagination: true,
    state: { pagination: { pageIndex: page, pageSize } },
    onPaginationChange: (updater) => {
      const next = typeof updater === "function" ? updater({ pageIndex: page, pageSize }) : updater;
      setPage(next.pageIndex);
    },
    getCoreRowModel: getCoreRowModel(),
  });

  return (
    <div className="space-y-4">
      {/* Filters */}
      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1">
          <span className="font-medium text-muted-foreground text-xs">{t("table.month")}</span>
          <MonthPicker value={month} onChange={handleMonthChange} />
        </div>

        <div className="space-y-1">
          <span className="font-medium text-muted-foreground text-xs">{tc("status")}</span>
          <Select value={status || "all-statuses"} onValueChange={handleStatusChange}>
            <SelectTrigger className="w-36" aria-label={tc("status")}>
              <SelectValue placeholder={t("table.allStatuses")} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all-statuses">{t("table.allStatuses")}</SelectItem>
              {WORKORDER_STATUSES.map((s) => (
                <SelectItem key={s} value={s}>
                  {t.has(`status.${s}`) ? t(`status.${s}`) : s}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="space-y-1">
          <span className="font-medium text-muted-foreground text-xs">{t("category")}</span>
          <Select value={categoryCode || "all-categories"} onValueChange={handleCategoryChange}>
            <SelectTrigger className="w-44" aria-label={t("category")}>
              <SelectValue placeholder={t("table.allCategories")} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all-categories">{t("table.allCategories")}</SelectItem>
              {(categoriesRes ?? []).map((category) => (
                <SelectItem key={category.code} value={category.code}>
                  {category.code} · {category.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="space-y-1">
          <span className="font-medium text-muted-foreground text-xs">{tc("search")}</span>
          <div className="relative">
            <SearchIcon className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              value={search}
              onChange={(event) => handleSearchChange(event.target.value)}
              placeholder={t("table.searchPlaceholder")}
              className="w-56 pl-8"
              aria-label={t("table.searchAria")}
            />
          </div>
        </div>

        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => void refetch()}
          aria-label={t("table.refreshAria")}
          title={tc("refresh")}
          disabled={isFetching}
        >
          <RefreshCwIcon className={isFetching ? "size-4 animate-spin" : "size-4"} />
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
          <p className="text-muted-foreground text-sm">{t("table.loadFailed")}</p>
          <Button type="button" variant="outline" size="sm" onClick={() => void refetch()}>
            {tc("retry")}
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
            size={pageSize}
            totalElements={data.total}
            onPageChange={setPage}
            onSizeChange={handlePageSizeChange}
            pageSizeOptions={PAGE_SIZE_OPTIONS}
          />
        </div>
      ) : null}

      {/* Empty state */}
      {!isLoading && !isError && data && data.items.length === 0 ? (
        <div className="flex flex-col items-center gap-2 rounded-lg border border-dashed p-8 text-center">
          <p className="text-muted-foreground text-sm">{t("table.empty")}</p>
        </div>
      ) : null}
    </div>
  );
}

/** Renders a workorder status as a coloured badge (Open = red, in-flight = amber, procurement = blue, done = green). */
function StatusBadge({ status }: { status: string }) {
  const t = useTranslations("workOrders");
  return <Badge className={statusClass(status)}>{t.has(`status.${status}`) ? t(`status.${status}`) : status}</Badge>;
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
