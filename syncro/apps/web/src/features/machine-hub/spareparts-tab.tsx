"use client";

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { Progress } from "@/components/ui/progress";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useListMachineSparepartInstallations } from "@/lib/api/generated/syncro";

export interface SparepartsTabProps {
  machineId?: string;
}

export function SparepartsTab({ machineId }: SparepartsTabProps) {
  const { data, isLoading, isError } = useListMachineSparepartInstallations(
    { machineId, pageable: { page: 0, size: 100, sort: ["installedAt,desc"] } },
    { query: { enabled: Boolean(machineId), staleTime: 30_000 } },
  );

  const items = data?.data?.items ?? [];

  if (!machineId || isLoading) {
    return (
      <div className="space-y-3">
        <Skeleton className="h-40 w-full" />
      </div>
    );
  }

  if (isError) {
    return <EmptyState title="Spareparts unavailable" description="Failed to load installed spareparts." />;
  }

  if (items.length === 0) {
    return (
      <EmptyState title="No spareparts installed" description="This machine currently has no spareparts installed." />
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Installed Spareparts</CardTitle>
        <CardDescription>Current installations and lifetime consumption</CardDescription>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Sparepart</TableHead>
              <TableHead>Function</TableHead>
              <TableHead className="hidden md:table-cell">Installed</TableHead>
              <TableHead className="w-64">Lifetime Usage</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {items.map((item) => {
              const pct = Math.min(Number(item.consumedPercentage ?? 0), 100);
              return (
                <TableRow key={item.id}>
                  <TableCell>
                    <div className="font-medium">{item.sparepartName ?? item.sparepartCode}</div>
                    <div className="text-xs text-muted-foreground">{item.sparepartCode}</div>
                  </TableCell>
                  <TableCell>{item.functionName ?? "-"}</TableCell>
                  <TableCell className="hidden md:table-cell">
                    {item.installedAt ? new Date(item.installedAt).toLocaleDateString() : "-"}
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-2">
                      <Progress value={pct} className="h-2 flex-1" />
                      <span className="w-12 text-right text-xs text-muted-foreground">{pct.toFixed(0)}%</span>
                    </div>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}
