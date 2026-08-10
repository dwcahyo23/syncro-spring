"use client";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import type { MachineView } from "@/lib/api/generated/model";

export interface OverviewTabProps {
  machine?: MachineView;
  isLoading?: boolean;
}

export function OverviewTab({ machine, isLoading }: OverviewTabProps) {
  if (isLoading || !machine) {
    return (
      <div className="space-y-6">
        <Card>
          <CardHeader>
            <Skeleton className="h-6 w-48" />
            <Skeleton className="h-4 w-64" />
          </CardHeader>
          <CardContent className="grid gap-4 md:grid-cols-2">
            {[0, 1, 2, 3, 4].map((i) => (
              <Skeleton key={i} className="h-12 w-full" />
            ))}
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle>Machine Details</CardTitle>
          <CardDescription>Master data and lifecycle information</CardDescription>
        </CardHeader>
        <CardContent>
          <dl className="grid gap-x-8 gap-y-4 text-sm md:grid-cols-2">
            <div>
              <dt className="font-medium text-muted-foreground">Code</dt>
              <dd className="mt-1">{machine.code}</dd>
            </div>
            <div>
              <dt className="font-medium text-muted-foreground">Name</dt>
              <dd className="mt-1">{machine.name ?? "-"}</dd>
            </div>
            <div>
              <dt className="font-medium text-muted-foreground">Plant</dt>
              <dd className="mt-1">{machine.plantName ?? "-"}</dd>
            </div>
            <div>
              <dt className="font-medium text-muted-foreground">Machine Group</dt>
              <dd className="mt-1">{machine.machineGroupName ?? "-"}</dd>
            </div>
            <div>
              <dt className="font-medium text-muted-foreground">Status</dt>
              <dd className="mt-1">
                <Badge variant={machine.status === "ACTIVE" ? "default" : "secondary"}>{machine.status ?? "-"}</Badge>
              </dd>
            </div>
            <div>
              <dt className="font-medium text-muted-foreground">Brand</dt>
              <dd className="mt-1">{machine.brand ?? "-"}</dd>
            </div>
            <div>
              <dt className="font-medium text-muted-foreground">Installed At</dt>
              <dd className="mt-1">{machine.installedAt ?? "-"}</dd>
            </div>
            <div>
              <dt className="font-medium text-muted-foreground">Telemetry Fields</dt>
              <dd className="mt-1">
                {machine.optionalTelemetryFields && machine.optionalTelemetryFields.length > 0
                  ? machine.optionalTelemetryFields.join(", ")
                  : "-"}
              </dd>
            </div>
          </dl>
          {machine.notes && (
            <div className="mt-4">
              <dt className="text-sm font-medium text-muted-foreground">Notes</dt>
              <dd className="mt-1 whitespace-pre-wrap rounded-md bg-muted p-3 text-sm">{machine.notes}</dd>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
