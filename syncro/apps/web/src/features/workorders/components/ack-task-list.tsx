"use client";

import { CheckCircle2, Clock, Star } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Empty, EmptyDescription, EmptyTitle } from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { useAcknowledge, useAckTaskList } from "@/features/workorders/hooks/use-ack-task-list";

/**
 * The 4-hour ack landing task list (story 14-4, FR-181). Shows acknowledged vs pending
 * acks and rated vs unrated closed workorders. Reached from the auto-login link in the
 * WAHA ack message; acknowledging a workorder records the action and stops escalation.
 * Loading / empty / error states required (project rule).
 */
export function AckTaskList() {
  const t = useTranslations("workOrders");
  const tc = useTranslations("common");
  const format = useFormatter();
  const { data, isLoading, isError, refetch } = useAckTaskList();
  const acknowledge = useAcknowledge();

  if (isLoading) {
    return (
      <div className="grid gap-4 md:grid-cols-2">
        {[0, 1].map((index) => (
          <div key={index} className="space-y-3 rounded-lg border p-4">
            <Skeleton className="h-5 w-32" />
            <Skeleton className="h-4 w-full" />
            <Skeleton className="h-8 w-full" />
          </div>
        ))}
      </div>
    );
  }

  if (isError) {
    return (
      <Card>
        <CardContent className="flex flex-col items-center gap-3 py-8">
          <p className="text-muted-foreground text-sm">{t("ackTaskList.list.loadFailed")}</p>
          <Button type="button" variant="outline" size="sm" onClick={() => void refetch()}>
            {tc("retry")}
          </Button>
        </CardContent>
      </Card>
    );
  }

  const list = data;
  const hasAnything =
    list &&
    (list.acknowledged.length > 0 || list.pending.length > 0 || list.rated.length > 0 || list.unrated.length > 0);

  if (!hasAnything) {
    return (
      <Card>
        <CardContent className="flex flex-col items-center gap-2 py-8">
          <Empty className="min-h-32">
            <EmptyTitle>{t("ackTaskList.list.noTasks")}</EmptyTitle>
            <EmptyDescription>{t("ackTaskList.list.noTasksDesc")}</EmptyDescription>
          </Empty>
        </CardContent>
      </Card>
    );
  }

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Clock aria-hidden="true" className="size-4" />
            {t("ackTaskList.list.pendingTitle")}
          </CardTitle>
          <CardDescription>{t("ackTaskList.list.pendingDesc")}</CardDescription>
        </CardHeader>
        <CardContent>
          {list && list.pending.length === 0 ? (
            <p className="text-muted-foreground text-sm">{t("ackTaskList.list.nonePending")}</p>
          ) : (
            <ul className="divide-y">
              {(list?.pending ?? []).map((entry) => (
                <li key={entry.workOrderId} className="flex items-center justify-between gap-3 py-2">
                  <div className="flex items-center gap-2">
                    <Badge variant="secondary">{entry.workOrderId}</Badge>
                    <span className="text-muted-foreground text-sm">{t("ackTaskList.list.pendingBadge")}</span>
                  </div>
                  <Button
                    type="button"
                    size="sm"
                    onClick={() => acknowledge.mutate(entry.workOrderId)}
                    disabled={acknowledge.isPending}
                  >
                    {t("ackTaskList.list.acknowledge")}
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <CheckCircle2 aria-hidden="true" className="size-4" />
            {t("ackTaskList.list.acknowledgedTitle")}
          </CardTitle>
          <CardDescription>{t("ackTaskList.list.acknowledgedDesc")}</CardDescription>
        </CardHeader>
        <CardContent>
          {list && list.acknowledged.length === 0 ? (
            <p className="text-muted-foreground text-sm">{t("ackTaskList.list.noneAcknowledged")}</p>
          ) : (
            <ul className="divide-y">
              {(list?.acknowledged ?? []).map((entry) => (
                <li key={entry.workOrderId} className="flex items-center justify-between gap-3 py-2">
                  <Badge variant="outline">{entry.workOrderId}</Badge>
                  <span className="text-muted-foreground text-xs">
                    {entry.acknowledgedAt
                      ? format.dateTime(new Date(entry.acknowledgedAt), { dateStyle: "medium", timeStyle: "short" })
                      : ""}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Star aria-hidden="true" className="size-4" />
              {t("ackTaskList.list.ratedTitle")}
            </CardTitle>
          </CardHeader>
          <CardContent>
            {list && list.rated.length === 0 ? (
              <p className="text-muted-foreground text-sm">{t("ackTaskList.list.noneRated")}</p>
            ) : (
              <ul className="divide-y">
                {(list?.rated ?? []).map((entry) => (
                  <li key={entry.id} className="flex items-center justify-between gap-3 py-2">
                    <Badge variant="outline">{entry.id}</Badge>
                    <Badge variant="secondary">{t("ackTaskList.list.ratedBadge")}</Badge>
                  </li>
                ))}
              </ul>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Star aria-hidden="true" className="size-4" />
              {t("ackTaskList.list.unratedTitle")}
            </CardTitle>
          </CardHeader>
          <CardContent>
            {list && list.unrated.length === 0 ? (
              <p className="text-muted-foreground text-sm">{t("ackTaskList.list.noneUnrated")}</p>
            ) : (
              <ul className="divide-y">
                {(list?.unrated ?? []).map((entry) => (
                  <li key={entry.id} className="flex items-center justify-between gap-3 py-2">
                    <Badge variant="outline">{entry.id}</Badge>
                    <Badge variant="secondary">{t("ackTaskList.list.unratedBadge")}</Badge>
                  </li>
                ))}
              </ul>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
