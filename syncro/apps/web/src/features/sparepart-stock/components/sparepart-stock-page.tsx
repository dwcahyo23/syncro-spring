"use client";

import { useState } from "react";

import { useQuery } from "@tanstack/react-query";
import { Loader2, PackageSearchIcon, TriangleAlertIcon } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { DataTablePagination } from "@/components/ui/data-table-pagination";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { usePlantScope } from "@/features/plant-scope/plant-scope-store";
import { useAdjustStock, useListStock, useReorderWarnings } from "@/features/sparepart-stock/hooks/use-sparepart-stock";
import { syncroFetch } from "@/lib/api/orval-mutator";

const PAGE_SIZE = 50;

/** Minimal plant option for the plant filter. */
interface PlantOption {
  id: string;
  code: string;
  name: string;
}

/**
 * Stock page (story 12-4, FR-146): TanStack Table server-side, columns material code/plant/
 * on-hand/OP/OQ, reorder-warning badge, edit + adjust dialogs. Non-native shadcn controls.
 */
export function SparepartStockPageContent() {
  const { scope, activePlantId } = usePlantScope();
  const [selectedPlantId, setSelectedPlantId] = useState(activePlantId !== "all" ? activePlantId : "");

  const { data: plantItems } = useQuery<PlantOption[]>({
    queryKey: ["/api/v1/plants"],
    queryFn: async () => {
      const res = await syncroFetch<{ data: PlantOption[] }>("/api/v1/plants", { method: "GET" });
      return res.data ?? [];
    },
    staleTime: 60_000,
  });

  const availablePlants = (plantItems ?? []).filter((p) => {
    if (!scope?.availablePlants || scope.mode === "UNRESTRICTED") return true;
    return scope.availablePlants.some((ap) => ap.id === p.id);
  });

  const effectivePlantId = selectedPlantId || availablePlants[0]?.id || "";

  const { data: stockItems, isLoading, isError, refetch } = useListStock(effectivePlantId || null);
  const { data: warnings } = useReorderWarnings(effectivePlantId || null);
  const warningCodes = new Set(warnings?.map((w) => w.materialCode) ?? []);

  const [page, setPage] = useState(0);
  const items = stockItems ?? [];
  const total = items.length;

  return (
    <div className="space-y-4">
      <div className="flex items-end justify-between gap-4">
        <div>
          <h1 className="font-semibold text-xl">Stock</h1>
          <p className="text-muted-foreground text-sm">
            Per-plant stock levels, reorder points and quantity recommendations.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Select
            value={effectivePlantId}
            onValueChange={(v) => {
              setSelectedPlantId(v);
              setPage(0);
            }}
          >
            <SelectTrigger className="w-48">
              <SelectValue placeholder="Select plant" />
            </SelectTrigger>
            <SelectContent>
              {availablePlants.map((plant) => (
                <SelectItem key={plant.id} value={plant.id}>
                  {plant.code} — {plant.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Button variant="outline" size="sm" onClick={() => void refetch()}>
            Refresh
          </Button>
        </div>
      </div>

      {isLoading ? (
        <div className="space-y-2">
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-10 w-full" />
        </div>
      ) : null}
      {isError ? (
        <Card>
          <CardContent className="flex flex-col items-center gap-3 py-12 text-center">
            <p className="text-muted-foreground text-sm">Failed to load stock data.</p>
            <Button variant="outline" size="sm" onClick={() => void refetch()}>
              Retry
            </Button>
          </CardContent>
        </Card>
      ) : null}
      {!isLoading && !isError && items.length === 0 ? (
        <Card>
          <CardContent className="flex flex-col items-center gap-3 py-16 text-center">
            <PackageSearchIcon className="size-10 text-muted-foreground" />
            <p className="font-medium">No stock rows for this plant.</p>
          </CardContent>
        </Card>
      ) : null}
      {!isLoading && !isError && items.length > 0 ? (
        <div className="rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Material code</TableHead>
                <TableHead>On hand</TableHead>
                <TableHead>Order point</TableHead>
                <TableHead>Order qty</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE).map((row) => (
                <TableRow key={row.materialCode}>
                  <TableCell className="font-mono text-xs">{row.materialCode}</TableCell>
                  <TableCell className="text-xs">{Number(row.stockOnHand).toLocaleString()}</TableCell>
                  <TableCell className="text-xs">{Number(row.orderPoint).toLocaleString()}</TableCell>
                  <TableCell className="text-xs">{Number(row.orderQty).toLocaleString()}</TableCell>
                  <TableCell>
                    {warningCodes.has(row.materialCode) ? (
                      <Badge variant="destructive" className="flex items-center gap-1">
                        <TriangleAlertIcon className="size-3" />
                        Reorder
                      </Badge>
                    ) : (
                      <Badge variant="outline">OK</Badge>
                    )}
                  </TableCell>
                  <TableCell>
                    <AdjustStockDialog
                      materialCode={row.materialCode}
                      plantId={effectivePlantId}
                      currentOnHand={row.stockOnHand}
                    />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <DataTablePagination
            page={page}
            size={PAGE_SIZE}
            totalElements={total}
            onPageChange={setPage}
            onSizeChange={() => setPage(0)}
            pageSizeOptions={[PAGE_SIZE]}
          />
        </div>
      ) : null}
    </div>
  );
}

function AdjustStockDialog({
  materialCode,
  plantId,
  currentOnHand,
}: {
  materialCode: string;
  plantId: string;
  currentOnHand: string;
}) {
  const [open, setOpen] = useState(false);
  const [delta, setDelta] = useState("");
  const adjust = useAdjustStock();

  const handleSubmit = () => {
    const num = Number(delta);
    if (Number.isNaN(num) || num === 0) return;
    adjust.mutate(
      { materialCode, data: { plantId, delta: num } },
      {
        onSuccess: () => {
          setOpen(false);
          setDelta("");
        },
      },
    );
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="outline" size="sm">
          Adjust
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Adjust stock — {materialCode}</DialogTitle>
          <DialogDescription>
            Current on-hand: {Number(currentOnHand).toLocaleString()}. Use a positive delta to restock, negative to
            withdraw.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="delta">Delta (signed adjustment)</Label>
            <Input
              id="delta"
              type="number"
              step="0.01"
              placeholder="e.g. -5 or +10"
              value={delta}
              onChange={(e) => setDelta(e.target.value)}
            />
          </div>
          <Button onClick={handleSubmit} disabled={adjust.isPending || !delta.trim()}>
            {adjust.isPending ? <Loader2 className="size-4 animate-spin" /> : null}
            Confirm adjustment
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
