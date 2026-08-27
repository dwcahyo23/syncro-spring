"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { useQuery } from "@tanstack/react-query";
import { PackageSearchIcon, PlusIcon, ShoppingCartIcon, Trash2Icon } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import { useCreateSparepartRequest } from "@/features/sparepart-requests/hooks/use-sparepart-requests";
import type { SparepartRequestType } from "@/features/sparepart-requests/types";
import { useListMachines } from "@/lib/api/generated/syncro";
import { syncroFetch } from "@/lib/api/orval-mutator";

interface CartItem {
  key: string;
  requestType: SparepartRequestType;
  machineId: string | null;
  /** BOM code (or sparepart id) when a known sparepart is picked; raw material code otherwise. */
  materialCode: string | null;
  quantity: number;
  purchaseUrl: string;
  estUnitPrice: string;
  notes: string;
  sparepartLabel?: string;
}

let _cartKey = 0;
function nextKey() {
  return `cart-${++_cartKey}`;
}

/**
 * Upgraded request-part dialog (2026-08-28): searchable BOM lookup + local draft cart.
 * Items sit in the cart until the user clicks "Send to warehouse" — no premature
 * submission. Unknown parts (no material code) start PENDING_COMPLETION per FR-144.
 */
export function RequestPartDialog({ workOrderId }: { workOrderId?: string | null }) {
  const createRequest = useCreateSparepartRequest();
  const [open, setOpen] = useState(false);
  const { data: machinesRes, isLoading: isLoadingMachines } = useListMachines({ page: 0, size: 100 });

  // ── Cart state ──────────────────────────────────────────────────────
  const [cart, setCart] = useState<CartItem[]>([]);

  // ── Add-item form ───────────────────────────────────────────────────
  const [requestType, setRequestType] = useState<SparepartRequestType>("SPAREPART");
  const [machineId, setMachineId] = useState("");
  const [materialCode, setMaterialCode] = useState("");
  const [quantity, setQuantity] = useState(1);
  const [purchaseUrl, setPurchaseUrl] = useState("");
  const [estUnitPrice, setEstUnitPrice] = useState("");
  const [notes, setNotes] = useState("");
  const [searchQuery, setSearchQuery] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const searchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const handleSearchChange = useCallback((value: string) => {
    setSearchQuery(value);
    clearTimeout(searchTimerRef.current ?? undefined);
    searchTimerRef.current = setTimeout(() => {
      setDebouncedSearch(value);
    }, 300);
  }, []);

  // Cleanup the pending debounce timer on unmount.
  useEffect(() => {
    return () => clearTimeout(searchTimerRef.current ?? undefined);
  }, []);

  // ── BOM search ──────────────────────────────────────────────────────
  const { data: bomRes, isLoading: bomLoading } = useQuery({
    queryKey: ["/api/v1/spareparts", debouncedSearch],
    queryFn: async () => {
      const params = new URLSearchParams();
      if (debouncedSearch) params.set("search", debouncedSearch);
      params.set("page", "0");
      params.set("size", "20");
      const res = await syncroFetch<{
        data: {
          items: Array<{
            id: string;
            code: string;
            materialCode: string | null;
            kind?: { name: string };
            brand?: { name: string };
          }>;
        };
      }>(`/api/v1/spareparts?${params.toString()}`, { method: "GET" });
      return res.data.items ?? [];
    },
    enabled: open,
    staleTime: 30_000,
  });

  const selectedBom = useMemo(() => {
    if (!materialCode) return null;
    return (bomRes ?? []).find(
      (s) => s.code === materialCode || s.materialCode === materialCode || s.id === materialCode,
    );
  }, [bomRes, materialCode]);

  // Clicking a search result selects the sparepart: keep the BOM code as the
  // material-code input (backend resolves by code) and remember the label.
  const selectBom = (sparepart: {
    id: string;
    code: string;
    materialCode: string | null;
    kind?: { name: string };
    brand?: { name: string };
  }) => {
    setMaterialCode(sparepart.code);
    setSearchQuery("");
    setDebouncedSearch("");
  };

  // ── Reset form ──────────────────────────────────────────────────────
  const resetForm = () => {
    setRequestType("SPAREPART");
    setMachineId("");
    setMaterialCode("");
    setQuantity(1);
    setPurchaseUrl("");
    setEstUnitPrice("");
    setNotes("");
    setSearchQuery("");
    setDebouncedSearch("");
  };

  const resetAll = () => {
    setCart([]);
    resetForm();
  };

  // ── Add to cart ─────────────────────────────────────────────────────
  const addToCart = () => {
    const item: CartItem = {
      key: nextKey(),
      requestType: requestType as SparepartRequestType,
      machineId: machineId || null,
      materialCode: materialCode || null,
      quantity,
      purchaseUrl: purchaseUrl || "",
      estUnitPrice: estUnitPrice || "",
      notes: notes || "",
      sparepartLabel: selectedBom ? `${selectedBom.kind?.name ?? ""} · ${selectedBom.brand?.name ?? ""}` : undefined,
    };
    setCart((prev) => [...prev, item]);
    resetForm();
  };

  const removeFromCart = (key: string) => {
    setCart((prev) => prev.filter((i) => i.key !== key));
  };

  // ── Submit all ──────────────────────────────────────────────────────
  const [submitting, setSubmitting] = useState(false);
  const handleSubmitAll = async () => {
    if (cart.length === 0) return;
    setSubmitting(true);
    // Fire all creates in parallel; failures are handled individually by the hook's toast.
    await Promise.allSettled(
      cart.map((item) =>
        createRequest.mutateAsync({
          requestType: item.requestType,
          workOrderId: workOrderId ?? null,
          machineId: item.machineId,
          materialCode: item.materialCode,
          quantity: item.quantity,
          estUnitPrice: item.estUnitPrice ? Number(item.estUnitPrice) : null,
          purchaseReferenceUrl: item.purchaseUrl || null,
          notes: item.notes || null,
        }),
      ),
    );
    setSubmitting(false);
    setOpen(false);
    resetAll();
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) resetAll();
      }}
    >
      <DialogTrigger asChild>
        <Button type="button" variant="outline" size="sm">
          <PackageSearchIcon className="mr-1 size-3" />
          Request part
        </Button>
      </DialogTrigger>
      <DialogContent className="top-4 max-h-[calc(100svh-2rem)] translate-y-0 overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Request part</DialogTitle>
          <DialogDescription>
            Search existing spareparts or add a new material code. Items sit in a draft cart — submit to warehouse only
            when ready.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4">
          {/* ── BOM search ───────────────────────────────── */}
          <div className="space-y-1">
            <Label>Search existing sparepart (BOM, material code, name)</Label>
            <Input
              placeholder="Type to search..."
              value={searchQuery}
              onChange={(e) => handleSearchChange(e.target.value)}
            />
            {bomLoading && <Skeleton className="h-8 w-full" />}
            {bomRes && bomRes.length > 0 && debouncedSearch && (
              <div className="max-h-32 overflow-y-auto rounded border">
                {bomRes.map((sp) => (
                  <button
                    key={sp.id}
                    type="button"
                    className="flex w-full items-center justify-between px-3 py-1.5 text-left text-xs hover:bg-muted"
                    onClick={() => selectBom(sp)}
                  >
                    <span className="font-medium">{sp.code}</span>
                    <span className="text-muted-foreground">
                      {sp.kind?.name} · {sp.brand?.name}
                      {sp.materialCode ? ` · MC:${sp.materialCode}` : ""}
                    </span>
                  </button>
                ))}
              </div>
            )}
          </div>

          {selectedBom && (
            <div className="rounded-md border border-emerald-200 bg-emerald-50/40 px-3 py-2 text-xs dark:border-emerald-900/40 dark:bg-emerald-950/20">
              <span className="font-medium">Selected:</span> {selectedBom.code} · {selectedBom.kind?.name} ·{" "}
              {selectedBom.brand?.name}
              {selectedBom.materialCode ? ` · MC: ${selectedBom.materialCode}` : ""}
            </div>
          )}

          {/* ── Add-item form ─────────────────────────────── */}
          <div className="grid grid-cols-2 gap-3 rounded-lg border p-3">
            <div className="space-y-1">
              <Label>Type</Label>
              <Select value={requestType} onValueChange={(v) => setRequestType(v as SparepartRequestType)}>
                <SelectTrigger className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="SPAREPART">Sparepart</SelectItem>
                  <SelectItem value="CONSUMABLE">Consumable</SelectItem>
                  <SelectItem value="SERVICE_EXTERNAL">External service</SelectItem>
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-1">
              <Label>Machine</Label>
              <Select value={machineId || undefined} onValueChange={setMachineId}>
                <SelectTrigger className="w-full" disabled={isLoadingMachines}>
                  <SelectValue placeholder={isLoadingMachines ? "Loading machines..." : "Select machine"} />
                </SelectTrigger>
                <SelectContent>
                  {(machinesRes?.data?.items ?? []).map((m) => (
                    <SelectItem key={m.id} value={m.id ?? ""}>
                      {m.code} · {m.name} · {m.plantCode}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-1">
              <Label>Material code (or leave blank for new part)</Label>
              <Input
                placeholder="e.g. MC-0001"
                value={materialCode}
                onChange={(e) => setMaterialCode(e.target.value)}
              />
            </div>

            <div className="space-y-1">
              <Label>Qty</Label>
              <Input type="number" min={1} value={quantity} onChange={(e) => setQuantity(Number(e.target.value))} />
            </div>

            <div className="space-y-1">
              <Label>Est. unit price (optional)</Label>
              <Input type="number" min={0} value={estUnitPrice} onChange={(e) => setEstUnitPrice(e.target.value)} />
            </div>

            <div className="space-y-1">
              <Label>Purchase URL (optional)</Label>
              <Input placeholder="https://..." value={purchaseUrl} onChange={(e) => setPurchaseUrl(e.target.value)} />
            </div>

            <div className="col-span-2 space-y-1">
              <Label>Notes (optional)</Label>
              <Textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={2} />
            </div>

            <div className="col-span-2 flex justify-end">
              <Button type="button" size="sm" variant="outline" onClick={addToCart} disabled={quantity < 1}>
                <PlusIcon className="mr-1 size-4" />
                Add to cart
              </Button>
            </div>
          </div>

          {/* ── Cart ──────────────────────────────────────── */}
          <div className="space-y-2">
            <div className="flex items-center gap-2">
              <ShoppingCartIcon className="size-4 text-muted-foreground" />
              <span className="font-medium text-sm">Cart</span>
              <Badge variant="secondary" className="ml-auto">
                {cart.length} item{cart.length !== 1 ? "s" : ""}
              </Badge>
              {/* ponytail: keep badge single-line; length never needs truncation */}
            </div>

            {cart.length === 0 ? (
              <p className="text-muted-foreground text-xs">Add items above. They stay here locally until you submit.</p>
            ) : (
              <ScrollArea className="max-h-48">
                <div className="space-y-2">
                  {cart.map((item) => (
                    <div key={item.key} className="flex items-start justify-between rounded-md border p-2 text-xs">
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2">
                          <span className="font-medium">{item.materialCode ?? "New part"}</span>
                          <Badge variant="outline" className="text-[10px]">
                            {item.requestType}
                          </Badge>
                        </div>
                        {item.sparepartLabel && <p className="text-muted-foreground">{item.sparepartLabel}</p>}
                        <p className="text-muted-foreground">
                          Qty: {item.quantity}
                          {item.estUnitPrice ? ` · Rp ${Number(item.estUnitPrice).toLocaleString()}` : ""}
                        </p>
                        {item.notes && <p className="italic">{item.notes}</p>}
                      </div>
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon"
                        className="size-6 shrink-0"
                        onClick={() => removeFromCart(item.key)}
                      >
                        <Trash2Icon className="size-3" />
                      </Button>
                    </div>
                  ))}
                </div>
              </ScrollArea>
            )}
          </div>
        </div>

        <DialogFooter className="flex-row justify-between gap-2">
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => {
              setOpen(false);
              resetAll();
            }}
          >
            Cancel
          </Button>
          <Button size="sm" onClick={handleSubmitAll} disabled={cart.length === 0 || submitting}>
            {submitting ? "Submitting…" : `Send to warehouse (${cart.length})`}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
