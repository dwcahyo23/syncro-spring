"use client";

import { Suspense, useEffect } from "react";

import { useSearchParams } from "next/navigation";

import { useTranslations } from "next-intl";

import { AckTaskList } from "@/features/workorders/components/ack-task-list";
import { ACK_AUTO_LOGIN_STORAGE_KEY } from "@/features/workorders/hooks/use-ack-task-list";

/**
 * The 4-hour ack landing page (story 14-4, FR-181). Reached from the auto-login link in
 * the WAHA ack message: {@code /dashboard/workorders/ack-task-list?token=<auto-login JWT>}.
 * The JWT filter accepts AUTO_LOGIN tokens for this endpoint; syncroFetch prefers the
 * stored ack token on the ack-task-list path so the task list loads without a password
 * login. The token is single-use and short-lived — any mismatch falls back to normal login.
 */
export default function AckTaskListPage() {
  const t = useTranslations("workOrders");
  return (
    <Suspense fallback={<p className="p-6 text-muted-foreground">{t("ackTaskList.page.loading")}</p>}>
      <AckTaskListPageInner />
    </Suspense>
  );
}

function AckTaskListPageInner() {
  const t = useTranslations("workOrders");
  const searchParams = useSearchParams();
  const token = searchParams.get("token");

  // Store the AUTO_LOGIN token for the page's lifetime; syncroFetch picks it up on the
  // ack-task-list path only. Restore any prior stored value on unmount.
  useEffect(() => {
    if (!token) {
      return;
    }
    const prior = window.localStorage.getItem(ACK_AUTO_LOGIN_STORAGE_KEY);
    window.localStorage.setItem(ACK_AUTO_LOGIN_STORAGE_KEY, token);
    return () => {
      if (prior === null) {
        window.localStorage.removeItem(ACK_AUTO_LOGIN_STORAGE_KEY);
      } else {
        window.localStorage.setItem(ACK_AUTO_LOGIN_STORAGE_KEY, prior);
      }
    };
  }, [token]);

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-col gap-6 p-6">
      <header className="space-y-1">
        <p className="font-medium text-muted-foreground text-sm">Syncro</p>
        <h1 className="font-semibold text-3xl tracking-tight">{t("ackTaskList.page.title")}</h1>
        <p className="text-muted-foreground">{t("ackTaskList.page.subtitle")}</p>
      </header>
      <AckTaskList />
    </main>
  );
}
