"use client";

import { useTranslations } from "next-intl";

import { SetupCompletenessChecklist } from "@/components/syncro/setup-completeness-checklist";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { usePlantScope } from "@/features/plant-scope/plant-scope-store";
import { useGetSetupCompleteness } from "@/lib/api/generated/syncro";
import { useAuthUser } from "@/lib/auth/use-auth-user";

export function SetupCompletenessPage() {
  const t = useTranslations("setup");
  const tc = useTranslations("common");
  const user = useAuthUser();
  const { scope } = usePlantScope();
  const canMutate = user?.applicationRole === "SUPER_ADMIN" || user?.applicationRole === "MANAGER_MAINTENANCE";
  const setupCompleteness = useGetSetupCompleteness({
    query: { enabled: Boolean(scope) },
  });
  const data = setupCompleteness.data?.data;

  return (
    <div className="space-y-4">
      {setupCompleteness.isLoading || !scope ? <SetupCompletenessSkeleton /> : null}
      {setupCompleteness.isError ? (
        <Card>
          <CardHeader>
            <CardTitle>{t("errorTitle")}</CardTitle>
            <CardDescription>{t("errorDescription")}</CardDescription>
          </CardHeader>
          <CardContent>
            <Button variant="outline" onClick={() => void setupCompleteness.refetch()}>
              {tc("retry")}
            </Button>
          </CardContent>
        </Card>
      ) : null}
      {!setupCompleteness.isLoading && !setupCompleteness.isError && data && (data.steps ?? []).length > 0 ? (
        <SetupCompletenessChecklist data={data} readOnly={!canMutate} />
      ) : null}
      {!setupCompleteness.isLoading && !setupCompleteness.isError && data && (data.steps ?? []).length === 0 ? (
        <Card>
          <CardHeader>
            <CardTitle>{t("emptyTitle")}</CardTitle>
            <CardDescription>{t("emptyDescription")}</CardDescription>
          </CardHeader>
        </Card>
      ) : null}
    </div>
  );
}

function SetupCompletenessSkeleton() {
  const t = useTranslations("setup");
  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("title")}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <Skeleton className="h-6 w-1/3" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
      </CardContent>
    </Card>
  );
}
