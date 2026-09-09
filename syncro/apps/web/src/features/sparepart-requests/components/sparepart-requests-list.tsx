"use client";

import { useState } from "react";

import { useQuery } from "@tanstack/react-query";
import { Loader2, PackageSearchIcon } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";

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
import { useNumberFormatter } from "@/lib/i18n/format";

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

/** Story 12-2: row actions available per current status (FR-141). Keys map to `sparepartRequests.actions.*`. */
function availableActions(status: SparepartRequestStatus): { actionKey: string; toStatus: SparepartRequestStatus }[] {
  switch (status) {
    case "REQUESTED":
    case "PENDING_COMPLETION":
      return [{ actionKey: "acknowledge", toStatus: "ACKED" }];
    case "ACKED":
      return [{ actionKey: "process", toStatus: "PROCESSING" }];
    case "PROCESSING":
      return [
        { actionKey: "markReady", toStatus: "READY" },
        { actionKey: "markPurchased", toStatus: "PURCHASE_REQUESTED" },
      ];
    case "PURCHASE_REQUESTED":
      return [{ actionKey: "partReceived", toStatus: "PART_RECEIVED" }];
    case "PART_RECEIVED":
      return [{ actionKey: "markReady", toStatus: "READY" }];
    case "READY":
      return [{ actionKey: "pickUp", toStatus: "PICKED_UP" }];
    case "PICKED_UP":
      return [{ actionKey: "close", toStatus: "CLOSED" }];
    default:
      return [];
  }
}

function MreDialog({ requestId }: { requestId: string }) {
  const t = useTranslations("sparepartRequests");
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
          {t("mre.trigger")}
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("mre.title")}</DialogTitle>
          <DialogDescription>{t("mre.description")}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="mreCode">{t("mre.codeLabel")}</Label>
            <Input
              id="mreCode"
              placeholder={t("mre.codePlaceholder")}
              value={mreCode}
              onChange={(e) => setMreCode(e.target.value)}
              maxLength={64}
            />
          </div>
          <Button onClick={handleSubmit} disabled={recordMre.isPending || !mreCode.trim()}>
            {recordMre.isPending ? <Loader2 className="size-4 animate-spin" /> : null}
            {t("mre.record")}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

export function SparepartRequestsPageContent() {
  const t = useTranslations("sparepartRequests");
  const tc = useTranslations("common");
  const format = useFormatter();
  const fmt = useNumberFormatter();
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
          <h1 className="font-semibold text-xl">{t("title")}</h1>
          <p className="text-muted-foreground text-sm">{t("description")}</p>
        </div>
        <Button variant="outline" size="sm" onClick={() => void refetch()}>
          {tc("refresh")}
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
            <p className="text-muted-foreground text-sm">{t("loadError")}</p>
            <Button variant="outline" size="sm" onClick={() => void refetch()}>
              {tc("retry")}
            </Button>
          </CardContent>
        </Card>
      ) : null}
      {!isLoading && !isError && items.length === 0 ? (
        <Card>
          <CardContent className="flex flex-col items-center gap-3 py-16 text-center">
            <PackageSearchIcon className="size-10 text-muted-foreground" />
            <p className="font-medium">{t("empty")}</p>
          </CardContent>
        </Card>
      ) : null}
      {!isLoading && !isError && items.length > 0 ? (
        <div className="rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("type")}</TableHead>
                <TableHead>{t("table.workorder")}</TableHead>
                <TableHead>{t("table.part")}</TableHead>
                <TableHead>{t("qty")}</TableHead>
                <TableHead>{tc("status")}</TableHead>
                <TableHead>{t("table.requested")}</TableHead>
                <TableHead>{tc("actions")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((request) => {
                const actions = availableActions(request.status);
                return (
                  <TableRow key={request.id}>
                    <TableCell className="text-xs">
                      {t.has(`types.${request.requestType}`) ? t(`types.${request.requestType}`) : request.requestType}
                    </TableCell>
                    <TableCell className="font-mono text-xs">{request.workOrderId ?? t("dash")}</TableCell>
                    <TableCell className="text-xs">
                      {request.materialCode ?? t("dash")}
                      {request.estUnitPrice
                        ? t("priceSuffix", { price: fmt.currency(Number(request.estUnitPrice), "IDR") })
                        : ""}
                    </TableCell>
                    <TableCell className="text-xs">{request.quantity}</TableCell>
                    <TableCell>
                      <div className="flex flex-wrap items-center gap-1">
                        <Badge variant={STATUS_VARIANTS[request.status] ?? "default"}>
                          {t.has(`status.${request.status}`) ? t(`status.${request.status}`) : request.status}
                        </Badge>
                        {request.status === "REQUESTED" || request.status === "PENDING_COMPLETION" ? (
                          <Badge variant="secondary">
                            {request.allowedActions.includes("approve") && request.requiredApprovalRole
                              ? t("pendingRole", { role: request.requiredApprovalRole })
                              : t("pendingApproval")}
                          </Badge>
                        ) : null}
                        {request.status === "ACKED" ? <Badge variant="outline">{t("approved")}</Badge> : null}
                      </div>
                    </TableCell>
                    <TableCell className="text-muted-foreground text-xs">
                      {format.dateTime(new Date(request.requestedAt), {
                        day: "numeric",
                        month: "short",
                        year: "numeric",
                        hour: "2-digit",
                        minute: "2-digit",
                        hour12: false,
                      })}
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
                            {t(`actions.${action.actionKey}`)}
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
