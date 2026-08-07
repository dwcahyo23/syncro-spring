"use client";

import { SetupCompletenessChecklist } from "@/components/syncro/setup-completeness-checklist";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { usePlantScope } from "@/features/plant-scope/plant-scope-store";
import { useGetSetupCompleteness } from "@/lib/api/generated/syncro";
import { useAuthUser } from "@/lib/auth/use-auth-user";

export function SetupCompletenessPage() {
  const user = useAuthUser();
  const { scope } = usePlantScope();
  const canMutate = user?.applicationRole === "SUPER_ADMIN" || user?.applicationRole === "MANAGE";
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
            <CardTitle>Setup completeness could not be loaded</CardTitle>
            <CardDescription>The setup status could not be fetched. Retry to load it again.</CardDescription>
          </CardHeader>
          <CardContent>
            <Button variant="outline" onClick={() => void setupCompleteness.refetch()}>
              Retry
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
            <CardTitle>No setup steps</CardTitle>
            <CardDescription>
              The setup completeness checklist is empty. Configure master data to see setup status.
            </CardDescription>
          </CardHeader>
        </Card>
      ) : null}
    </div>
  );
}

function SetupCompletenessSkeleton() {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Setup Completeness</CardTitle>
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
