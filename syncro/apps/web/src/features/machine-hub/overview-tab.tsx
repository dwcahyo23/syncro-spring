"use client";

import type { MachineView, SparepartMachineRefView } from "@/lib/api/generated/model";
import { EmptyState } from "@/components/ui/empty-state";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";

export interface OverviewTabProps {
  machine?: MachineView;
}

export function OverviewTab({ machine }: OverviewTabProps) {
  if (!machine) {
    return (
      <div className="flex min-h-[200px] items-center justify-center">
        <p className="text-muted-foreground">Loading overview...</p>
      </div>
    );
  }

  const data = {
    name: machine.name,
    plantName: machine.plantName,
    brand: machine.brand,
    status: machine.status,
    installedAt: machine.installedAt,
    notes: machine.notes,
  };

  if (!data.name && !data.plantName) {
    return <EmptyState title="Machine not found" description="The specified machine does not exist." />;
  }

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle>Machine Details</CardTitle>
          <CardDescription>Basic information about the machine</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-4 md:grid-cols-2">
            <div>
              <dt className="text-sm font-medium text-muted-foreground">Name</dt>
              <dd className="mt-1">{data.name || <Skeleton className="h-4 w-32" />}</dd>
            </div>
            <div>
              <dt className="text-sm font-medium text-muted-foreground">Plant</dt>
              <dd className="mt-1">{data.plantName || <Skeleton className="h-4 w-32" />}</dd>
            </div>
            <div>
              <dt className="text-sm font-medium text-muted-foreground">Brand</dt>
              <dd className="mt-1">{data.brand || "-"}</dd>
            </div>
            <div>
              <dt className="text-sm font-medium text-muted-foreground">Manual Status</dt>
              <dd className="mt-1">
                <Badge variant={data.status === "ACTIVE" ? "default" : "destructive"}>
                  {data.status || "-"}
                </Badge>
              </dd>
            </div>
            <div>
              <dt className="text-sm font-medium text-muted-foreground">Installed At</dt>
              <dd className="mt-1">{data.installedAt ? formatDateString(data.installedAt) : "-"}</dd>
            </div>
          </div>
          {data.notes && (
            <div>
              <dt className="text-sm font-medium text-muted-foreground">Notes</dt>
              <dd className="mt-1 whitespace-pre-wrap rounded-md bg-muted p-3">
                {data.notes}
              </dd>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function formatDateString(dateString: string): string {
  const date = new Date(dateString);
  return date.toLocaleDateString("en-US", {
    year: "numeric",
    month: "long",
    day: "numeric",
  });
}
