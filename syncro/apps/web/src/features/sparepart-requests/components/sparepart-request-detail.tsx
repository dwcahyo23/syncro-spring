"use client";

import { useMemo, useState } from "react";

import Link from "next/link";

import { useQuery } from "@tanstack/react-query";
import { ArrowLeftIcon, Loader2, PackageIcon } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { ApproveDialog } from "@/features/sparepart-requests/components/approve-dialog";
import { CompleteDialog } from "@/features/sparepart-requests/components/complete-dialog";
import { availableActions, MreDialog } from "@/features/sparepart-requests/components/sparepart-requests-list";
import { useTransitionRequest } from "@/features/sparepart-requests/hooks/use-sparepart-requests";
import type { SparepartRequestView } from "@/features/sparepart-requests/types";
import { syncroFetch } from "@/lib/api/orval-mutator";

const REQUESTS_KEY = "/api/v1/sparepart-requests";

/**
 * Sparepart-request cockpit for the WAREHOUSE/inventory flow (leader decision
 * 2026-09-14): status changes (ack/proses/ready/diterima/tutup), approval,
 * completion and MRE all belong HERE — never on the workorder page. Linked back
 * to the requesting workorder, which shows this request read-only.
 */
export function SparepartRequestDetailContent({ requestId }: { requestId: string }) {
  const t = useTranslations("sparepartRequests");
  const tc = useTranslations("common");
  const format = useFormatter();
  const transition = useTransitionRequest();
  const [pendingId, setPendingId] = useState(false);

  // No GET /{id} on the backend; read the scoped list and pick this row.
  const { data, isLoading, isError, refetch } = useQuery<SparepartRequestView[]>({
    queryKey: [REQUESTS_KEY, "all"],
    queryFn: async () => {
      const res = await syncroFetch<{ data: { items: SparepartRequestView[] } }>(`${REQUESTS_KEY}?page=0&size=200`, {
        method: "GET",
      });
      return res.data.items ?? [];
    },
    retry: false,
  });

  const request = useMemo(() => (data ?? []).find((r) => r.id === requestId), [data, requestId]);

  const fmt = (iso: string | null) =>
    iso ? format.dateTime(new Date(iso), { dateStyle: "medium", timeStyle: "short" }) : "-";

  if (isLoading) {
    return (
      <div className="mx-auto w-full max-w-2xl space-y-4 p-6">
        <Skeleton className="h-8 w-56" />
        <Skeleton className="h-40 w-full" />
      </div>
    );
  }

  if (isError) {
    return (
      <div className="mx-auto w-full max-w-2xl space-y-4 p-6">
        <BackLink />
        <p className="text-muted-foreground text-sm">{t("loadError")}</p>
        <Button variant="outline" size="sm" onClick={() => void refetch()}>
          {tc("retry")}
        </Button>
      </div>
    );
  }

  if (!request) {
    return (
      <div className="mx-auto w-full max-w-2xl space-y-4 p-6">
        <BackLink />
        <p className="text-muted-foreground text-sm">{t("detail.notFound")}</p>
      </div>
    );
  }

  const actions = availableActions(request.status);
  const isTerminal = request.status === "CLOSED";

  return (
    <div className="mx-auto w-full max-w-2xl space-y-4 p-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <BackLink />
        <Button variant="outline" size="sm" onClick={() => void refetch()} disabled={isLoading}>
          {tc("refresh")}
        </Button>
      </div>

      <section className="space-y-3 rounded-lg border p-4">
        <div className="flex flex-wrap items-center gap-2">
          <PackageIcon className="size-5 text-muted-foreground" />
          <h2 className="font-mono font-semibold">{request.materialCode ?? t("detail.newPart")}</h2>
          <Badge variant="outline" className="text-xs">
            {t.has(`types.${request.requestType}`) ? t(`types.${request.requestType}`) : request.requestType}
          </Badge>
          <Badge variant="secondary" className="text-xs">
            {t.has(`status.${request.status}`) ? t(`status.${request.status}`) : request.status}
          </Badge>
        </div>

        <dl className="grid grid-cols-2 gap-x-6 gap-y-2 text-sm">
          <Field label={tc("qty")}>{request.quantity}</Field>
          <Field label={t("detail.requestedBy")}>{request.requestedBy.slice(0, 8)}…</Field>
          <Field label={t("detail.requestedAt")}>{fmt(request.requestedAt)}</Field>
          <Field label={t("detail.updatedAt")}>{fmt(request.updatedAt)}</Field>
          <div className="col-span-2">
            <dt className="text-muted-foreground text-xs">{t("table.workorder")}</dt>
            <dd className="text-sm">
              {request.workOrderId ? (
                <Link href={`/dashboard/workorders/${request.workOrderId}`} className="font-mono hover:underline">
                  {request.workOrderId}
                </Link>
              ) : (
                t("dash")
              )}
            </dd>
          </div>
          {request.notes ? (
            <div className="col-span-2">
              <dt className="text-muted-foreground text-xs">{t("detail.notes")}</dt>
              <dd className="text-sm">{request.notes}</dd>
            </div>
          ) : null}
        </dl>
      </section>

      {/* Status-flow actions — the warehouse's own screen (role gates stay server-side). */}
      <section className="space-y-2 rounded-lg border p-4">
        <h3 className="font-semibold text-sm">{t("detail.statusFlow")}</h3>
        {isTerminal ? (
          <p className="text-muted-foreground text-sm">{t("detail.terminal")}</p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {actions.map((action) => (
              <Button
                key={action.toStatus}
                variant="outline"
                size="sm"
                disabled={pendingId}
                onClick={() => {
                  setPendingId(true);
                  transition.mutate(
                    { id: request.id, data: { toStatus: action.toStatus } },
                    {
                      onSettled: () => {
                        setPendingId(false);
                        void refetch();
                      },
                    },
                  );
                }}
              >
                {pendingId ? <Loader2 className="size-3 animate-spin" /> : null}
                {t(`actions.${action.actionKey}`)}
              </Button>
            ))}
            {request.allowedActions.includes("approve") ? <ApproveDialog requestId={request.id} /> : null}
            {request.allowedActions.includes("complete") ? <CompleteDialog requestId={request.id} /> : null}
            {request.status === "PURCHASE_REQUESTED" ? <MreDialog requestId={request.id} /> : null}
          </div>
        )}
        {request.requiredApprovalRole && request.status === "REQUESTED" ? (
          <p className="text-muted-foreground text-xs">{t("pendingRole", { role: request.requiredApprovalRole })}</p>
        ) : null}
      </section>
    </div>
  );
}

function BackLink() {
  const t = useTranslations("sparepartRequests");
  return (
    <Button asChild variant="ghost" size="sm" className="-ml-2 text-muted-foreground">
      <Link href="/dashboard/sparepart-requests">
        <ArrowLeftIcon className="mr-1 size-4" />
        {t("detail.back")}
      </Link>
    </Button>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-muted-foreground text-xs">{label}</dt>
      <dd className="text-sm">{children}</dd>
    </div>
  );
}
