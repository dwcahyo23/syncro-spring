"use client";

import { useState } from "react";

import { useQuery } from "@tanstack/react-query";
import { format } from "date-fns";
import { PackageSearchIcon } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { DataTablePagination } from "@/components/ui/data-table-pagination";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { syncroFetch } from "@/lib/api/orval-mutator";

interface SparepartRequestRow {
  id: string;
  requestType: "SPAREPART" | "CONSUMABLE" | "SERVICE_EXTERNAL";
  workOrderId: string | null;
  machineId: string | null;
  materialCode: string | null;
  quantity: number;
  estUnitPrice: string | null;
  purchaseReferenceUrl: string | null;
  status: string;
  requestedAt: string;
  notes: string | null;
}

const PAGE_SIZE = 20;
const TYPE_LABELS: Record<string, string> = {
  SPAREPART: "Sparepart",
  CONSUMABLE: "Consumable",
  SERVICE_EXTERNAL: "External service",
};

/** Type label helper kept for the table; filters are backend-scoped in 12-2. */

export function SparepartRequestsPageContent() {
  const [page, setPage] = useState(0);

  const { data, isLoading, isError, refetch } = useQuery<{ items: SparepartRequestRow[]; total: number }>({
    queryKey: ["/api/v1/sparepart-requests", page],
    queryFn: async () => {
      const params = new URLSearchParams();
      params.set("page", String(page));
      params.set("size", String(PAGE_SIZE));
      const res = await syncroFetch<{ data: { items: SparepartRequestRow[]; total: number } }>(
        `/api/v1/sparepart-requests?${params.toString()}`,
        { method: "GET" },
      );
      return res.data;
    },
    staleTime: 15_000,
  });

  const items = data?.items ?? [];
  const total = data?.total ?? 0;

  return (
    <div className="space-y-4">
      <div className="flex items-end justify-between gap-4">
        <div>
          <h1 className="font-semibold text-xl">Sparepart Requests</h1>
          <p className="text-muted-foreground text-sm">Track sparepart, consumable, and external-service requests.</p>
        </div>
        <Button variant="outline" size="sm" onClick={() => void refetch()}>
          Refresh
        </Button>
      </div>

      {isLoading ? (
        <div className="space-y-2">
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-10 w-full" />
        </div>
      ) : null}
      {isError ? (
        <Card>
          <CardContent className="flex flex-col items-center gap-3 py-12 text-center">
            <p className="text-muted-foreground text-sm">Failed to load requests.</p>
            <Button variant="outline" size="sm" onClick={() => void refetch()}>
              Retry
            </Button>
          </CardContent>
        </Card>
      ) : null}
      {!isLoading && !isError && items.length === 0 ? (
        <Card>
          <CardContent className="flex flex-col items-center gap-3 py-16 text-center">
            <PackageSearchIcon className="size-10 text-muted-foreground" />
            <p className="font-medium">No requests match this filter.</p>
          </CardContent>
        </Card>
      ) : null}
      {!isLoading && !isError && items.length > 0 ? (
        <div className="rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Type</TableHead>
                <TableHead>Workorder</TableHead>
                <TableHead>Part</TableHead>
                <TableHead>Qty</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Requested</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((request) => (
                <TableRow key={request.id}>
                  <TableCell className="text-xs">{TYPE_LABELS[request.requestType] ?? request.requestType}</TableCell>
                  <TableCell className="font-mono text-xs">{request.workOrderId ?? "-"}</TableCell>
                  <TableCell className="text-xs">
                    {request.materialCode ?? "-"}
                    {request.estUnitPrice ? ` · Rp ${Number(request.estUnitPrice).toLocaleString()}` : ""}
                  </TableCell>
                  <TableCell className="text-xs">{request.quantity}</TableCell>
                  <TableCell>
                    <Badge variant={request.status === "PENDING_COMPLETION" ? "secondary" : "default"}>
                      {request.status === "PENDING_COMPLETION" ? "Pending completion" : "Requested"}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-muted-foreground text-xs">
                    {format(new Date(request.requestedAt), "d MMM yyyy HH:mm")}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <DataTablePagination
            page={page}
            size={PAGE_SIZE}
            totalElements={total}
            onPageChange={setPage}
            onSizeChange={() => setPage(0)}
            pageSizeOptions={[PAGE_SIZE]}
          />
        </div>
      ) : null}
    </div>
  );
}
