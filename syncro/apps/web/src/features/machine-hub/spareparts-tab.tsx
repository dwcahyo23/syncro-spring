"use client";

import { useEffect, useState } from "react";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { SparepartMachineRefView } from "@/lib/api/generated/model";
import { EmptyState } from "@/components/ui/empty-state";

export interface SparepartsTabProps {
  machineCode: string;
}

interface CachedSparepartsData {
  spareparts?: SparepartMachineRefView[];
  loading?: boolean;
}

const cache = new Map<string, CachedSparepartsData>();

export function SparepartsTab({ machineCode }: SparepartsTabProps) {
  const [data, setData] = useState<CachedSparepartsData | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const fetchSpareparts = async () => {
    try {
      setIsLoading(true);
      const response = await fetch(
        `/api/v1/machines/${encodeURIComponent(machineCode)}/spareparts`
      );
      if (!response.ok) {
        throw new Error(`Failed to fetch spareparts: ${response.statusText}`);
      }
      const result = await response.json();

      const cachedData: CachedSparepartsData = {
        spareparts: result.items || [],
      };

      cache.set(machineCode, cachedData);
      setData(cachedData);
    } catch (error) {
      console.error("Error fetching spareparts:", error);
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
      fetchSpareparts();
    }
  }, [machineCode]);

  if (isLoading) {
    return (
      <div className="flex min-h-[200px] items-center justify-center">
        <p className="text-muted-foreground">Loading spareparts...</p>
      </div>
    );
  }

  if (!data?.spareparts || data.spareparts.length === 0) {
    return (
      <EmptyState
        title="No spareparts installed"
        description="This machine currently has no spareparts installed."
      />
    );
  }

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle>Spareparts Installed on Machine</CardTitle>
          <CardDescription>
            List of spareparts currently installed and their lifetime information
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Sparepart Code</TableHead>
                <TableHead>Installation Date</TableHead>
                <TableHead>Lifetime Hours</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.spareparts.map((sparepart, index) => (
                <TableRow key={index}>
                  <TableCell className="font-medium">{sparepart.sparepartCode}</TableCell>
                  <TableCell>
                    {sparepart.installationDate
                      ? formatDate(sparepart.installationDate)
                      : "-"}
                  </TableCell>
                  <TableCell>
                    {sparepart.lifetimeHours !== undefined
                      ? `${sparepart.lifetimeHours.toFixed(1)}h`
                      : "-"}
                  </TableCell>
                  <TableCell>
                    {sparepart.status ? (
                      <span className="text-sm text-muted-foreground">{sparepart.status}</span>
                    ) : (
                      "-"
                    )}
                  </TableCell>
                  <TableCell>
                    <a
                      href={`/dashboard/master-data/spareparts?code=${sparepart.sparepartCode}`}
                      className="text-primary hover:underline"
                    >
                      View details
                    </a>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Sparepart Lifetime Summary</CardTitle>
          <CardDescription>Overall statistics for spareparts on this machine</CardDescription>
        </CardHeader>
        <CardContent>
          <dl className="grid gap-4 sm:grid-cols-3">
            <div>
              <dt className="text-sm font-medium text-muted-foreground">Total Spareparts</dt>
              <dd className="mt-1 text-2xl font-bold">{data.spareparts.length}</dd>
            </div>
            <div>
              <dt className="text-sm font-medium text-muted-foreground">Average Lifetime</dt>
              <dd className="mt-1 text-2xl font-bold">
                {getAverageLifetime(data.spareparts)}h
              </dd>
            </div>
            <div>
              <dt className="text-sm font-medium text-muted-foreground">Active Installations</dt>
              <dd className="mt-1 text-2xl font-bold">
                {data.spareparts.filter((s) => !s.isRemoved).length}
              </dd>
            </div>
          </dl>
        </CardContent>
      </Card>
    </div>
  );
}

function formatDate(dateString: string): string {
  const date = new Date(dateString);
  return date.toLocaleDateString("en-US", {
    year: "numeric",
    month: "long",
    day: "numeric",
  });
}

function getAverageLifetime(spareparts: SparepartMachineRefView[]): number {
  const validValues = spareparts
    .map((s) => s.lifetimeHours)
    .filter((h): h is number => typeof h === "number" && !isNaN(h));
  
  if (validValues.length === 0) return 0;
  const sum = validValues.reduce((acc, val) => acc + val, 0);
  return sum / validValues.length;
}
