"use client";

import { useEffect, useState } from "react";
import type { MachineViewStatus, SparepartMachineRefView } from "@/lib/api/generated/model";
import { EmptyState } from "@/components/ui/empty-state";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";

export interface OverviewTabProps {
  machineCode: string;
}

interface CachedData {
  name?: string;
  status?: MachineViewStatus;
  brand?: string;
  installedAt?: string;
  notes?: string;
  plantName?: string;
  spareparts?: SparepartMachineRefView[];
}

const cache = new Map<string, CachedData>();

export function OverviewTab({ machineCode }: OverviewTabProps) {
  const [data, setData] = useState<CachedData | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const fetchMachineData = async () => {
    try {
      const response = await fetch(`/api/v1/machines/${encodeURIComponent(machineCode)}`);
      if (!response.ok) {
        throw new Error(`Failed to fetch machine: ${response.statusText}`);
      }
      const machine = await response.json();

      const sparepartsResponse = await fetch(
        `/api/v1/machines/${encodeURIComponent(machineCode)}/spareparts`
      );
      const sparepartsData = sparepartsResponse.ok ? await sparepartsResponse.json() : { items: [] };

      const cachedData: CachedData = {
        name: machine.name,
        status: machine.status,
        brand: machine.brand,
        installedAt: machine.installedAt,
        notes: machine.notes,
        plantName: machine.plantName,
        spareparts: sparepartsData.items || [],
      };

      cache.set(machineCode, cachedData);
      setData(cachedData);
    } catch (error) {
      console.error("Error fetching machine data:", error);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    const cached = cache.get(machineCode);
    if (cached) {
      setData(cached);
      setIsLoading(false);
    } else {
      fetchMachineData();
    }
  }, [machineCode]);

  if (isLoading) {
    return (
      <div className="flex min-h-[200px] items-center justify-center">
        <p className="text-muted-foreground">Loading overview...</p>
      </div>
    );
  }

  if (!data) {
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
              <dd className="mt-1">{data.name || "-"}</dd>
            </div>
            <div>
              <dt className="text-sm font-medium text-muted-foreground">Plant</dt>
              <dd className="mt-1">{data.plantName || "-"}</dd>
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

      {data.spareparts && data.spareparts.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Spareparts Currently Installed</CardTitle>
            <CardDescription>List of spareparts installed on this machine</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="flex flex-wrap gap-2">
              {data.spareparts.map((sparepart, index) => (
                <Badge key={index} variant="secondary">
                  {sparepart.sparepartCode}
                </Badge>
              ))}
            </div>
          </CardContent>
        </Card>
      )}
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
