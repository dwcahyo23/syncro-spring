"use client";

import { useState } from "react";

import { useQuery } from "@tanstack/react-query";
import { format } from "date-fns";
import { Loader2, PackageSearchIcon } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { DataTablePagination } from "@/components/ui/data-table-pagination";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ApproveDialog } from "@/features/sparepart-requests/components/approve-dialog";
import { CompleteDialog } from "@/features/sparepart-requests/components/complete-dialog";
import { useRecordMre, useTransitionRequest } from "@/features/sparepart-requests/hooks/use-sparepart-requests";
import type { SparepartRequestStatus } from "@/features/sparepart-requests/types";
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
  status: SparepartRequestStatus;
  requestedAt: string;
  notes: string | null;
  allowedActions: string[];
  requiredApprovalRole?: string | null;
}

const PAGE_SIZE = 20;
const TYPE_LABELS: Record<string, string> = {
  SPAREPART: "Sparepart",
  CONSUMABLE: "Consumable",
  SERVICE_EXTERNAL: "External service",
};

const STATUS_LABELS: Record<string, string> = {
  REQUESTED: "Requested",
  PENDING_COMPLETION: "Pending completion",
  ACKED: "Acknowledged",
  PROCESSING: "Processing",
  READY: "Ready",
  PURCHASE_REQUESTED: "Purchase requested",
  PART_RECEIVED: "Part received",
  PICKED_UP: "Picked up",
  CLOSED: "Closed",
};

const STATUS_VARIANTS: Record<string, "default" | "secondary" | "outline" | "ghost" | "destructive"> = {
  REQUESTED: "default",
  PENDING_COMPLETION: "secondary",
  ACKED: "outline",
  PROCESSING: "default",
  READY: "default",
  PURCHASE_REQUESTED: "secondary",
  PART_RECEIVED: "outline",
  PICKED_UP: "ghost",
  CLOSED: "ghost",
};

/** Story 12-2: row actions available per current status (FR-141). */
function availableActions(status: SparepartRequestStatus): { label: string; toStatus: SparepartRequestStatus }[] {
  switch (status) {
    case "REQUESTED":
    case "PENDING_COMPLETION":
      return [{ label: "Acknowledge", toStatus: "ACKED" }];
    case "ACKED":
      return [{ label: "Process", toStatus: "PROCESSING" }];
    case "PROCESSING":
      return [
        { label: "Mark Ready", toStatus: "READY" },
        { label: "Mark Purchased", toStatus: "PURCHASE_REQUESTED" },
      ];
    case "PURCHASE_REQUESTED":
      return [{ label: "Part Received", toStatus: "PART_RECEIVED" }];
    case "PART_RECEIVED":
      return [{ label: "Mark Ready", toStatus: "READY" }];
    case "READY":
      return [{ label: "Pick Up", toStatus: "PICKED_UP" }];
    case "PICKED_UP":
      return [{ label: "Close", toStatus: "CLOSED" }];
    default:
      return [];
  }
}

function MreDialog({ requestId }: { requestId: string }) {
  const [open, setOpen] = useState(false);
  const [mreCode, setMreCode] = useState("");
  const recordMre = useRecordMre();

  const handleSubmit = () => {
    if (!mreCode.trim()) return;
    recordMre.mutate(
      { id: requestId, data: { mreCode: mreCode.trim() } },
      {
        onSuccess: () => {
          setOpen(false);
          setMreCode("");
        },
      },
    );
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="outline" size="sm">
          Record MRE
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Record MRE Code</DialogTitle>
          <DialogDescription>Enter the manual MRE (material request) code for this purchase.</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="mreCode">MRE Code</Label>
            <Input
              id="mreCode"
              placeholder="e.g. MRE26023xxxx"
              value={mreCode}
              onChange={(e) => setMreCode(e.target.value)}
              maxLength={64}
            />
          </div>
          <Button onClick={handleSubmit} disabled={recordMre.isPending || !mreCode.trim()}>
            {recordMre.isPending ? <Loader2 className="size-4 animate-spin" /> : null}
            Record
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

export function SparepartRequestsPageContent() {
  const [page, setPage] = useState(0);
  const [pendingRequestId, setPendingRequestId] = useState<string | null>(null);
  const transition = useTransitionRequest();

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
                <TableHead>Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((request) => {
                const actions = availableActions(request.status);
                return (
                  <TableRow key={request.id}>
                    <TableCell className="text-xs">{TYPE_LABELS[request.requestType] ?? request.requestType}</TableCell>
                    <TableCell className="font-mono text-xs">{request.workOrderId ?? "-"}</TableCell>
                    <TableCell className="text-xs">
                      {request.materialCode ?? "-"}
                      {request.estUnitPrice ? ` · Rp ${Number(request.estUnitPrice).toLocaleString()}` : ""}
                    </TableCell>
                    <TableCell className="text-xs">{request.quantity}</TableCell>
                    <TableCell>
                      <div className="flex flex-wrap items-center gap-1">
                        <Badge variant={STATUS_VARIANTS[request.status] ?? "default"}>
                          {STATUS_LABELS[request.status] ?? request.status}
                        </Badge>
                        {request.status === "REQUESTED" || request.status === "PENDING_COMPLETION" ? (
                          <Badge variant="secondary">
                            {request.allowedActions.includes("approve") && request.requiredApprovalRole
                              ? `Pending: ${request.requiredApprovalRole}`
                              : "Pending approval"}
                          </Badge>
                        ) : null}
                        {request.status === "ACKED" ? <Badge variant="outline">Approved</Badge> : null}
                      </div>
                    </TableCell>
                    <TableCell className="text-muted-foreground text-xs">
                      {format(new Date(request.requestedAt), "d MMM yyyy HH:mm")}
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-1">
                        {actions.map((action) => (
                          <Button
                            key={action.toStatus}
                            variant="outline"
                            size="sm"
                            disabled={pendingRequestId !== null}
                            onClick={() => {
                              setPendingRequestId(request.id);
                              transition.mutate(
                                { id: request.id, data: { toStatus: action.toStatus } },
                                { onSettled: () => setPendingRequestId(null) },
                              );
                            }}
                          >
                            {pendingRequestId === request.id ? <Loader2 className="size-3 animate-spin" /> : null}
                            {action.label}
                          </Button>
                        ))}
                        {request.allowedActions.includes("approve") ? <ApproveDialog requestId={request.id} /> : null}
                        {request.allowedActions.includes("complete") ? <CompleteDialog requestId={request.id} /> : null}
                        {request.status === "PURCHASE_REQUESTED" ? <MreDialog requestId={request.id} /> : null}
                      </div>
                    </TableCell>
                  </TableRow>
                );
              })}
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
