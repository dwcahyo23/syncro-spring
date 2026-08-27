"use client";

import { useState } from "react";

import { Button } from "@/components/ui/button";
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
import { Textarea } from "@/components/ui/textarea";
import { useCreateSparepartRequest } from "@/features/sparepart-requests/hooks/use-sparepart-requests";
import type { SparepartRequestType } from "@/features/sparepart-requests/types";

/**
 * "Request part" dialog (story 12-1, FR-140/FR-143/FR-144). Creates a sparepart request
 * with type rules (SPAREPART/CONSUMABLE/SERVICE_EXTERNAL), optional purchase reference
 * URL, and optional material code (unknown → PENDING_COMPLETION). Non-native shadcn
 * Select for the request type per project rule.
 */
export function RequestPartDialog({ workOrderId }: { workOrderId?: string | null }) {
  const createRequest = useCreateSparepartRequest(workOrderId);
  const [open, setOpen] = useState(false);
  const [requestType, setRequestType] = useState<SparepartRequestType>("SPAREPART");
  const [machineId, setMachineId] = useState("");
  const [materialCode, setMaterialCode] = useState("");
  const [quantity, setQuantity] = useState(1);
  const [purchaseUrl, setPurchaseUrl] = useState("");
  const [estUnitPrice, setEstUnitPrice] = useState("");
  const [notes, setNotes] = useState("");

  const reset = () => {
    setRequestType("SPAREPART");
    setMachineId("");
    setMaterialCode("");
    setQuantity(1);
    setPurchaseUrl("");
    setEstUnitPrice("");
    setNotes("");
  };

  const handleCreate = () => {
    createRequest.mutate(
      {
        requestType,
        workOrderId: workOrderId ?? null,
        machineId: machineId || null,
        materialCode: materialCode || null,
        quantity,
        estUnitPrice: estUnitPrice || null,
        purchaseReferenceUrl: purchaseUrl || null,
        notes: notes || null,
      },
      {
        onSuccess: () => {
          setOpen(false);
          reset();
        },
      },
    );
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button type="button" variant="outline" size="sm">
          Request part
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Request part</DialogTitle>
          <DialogDescription>Request a sparepart, consumable, or external service.</DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <div className="space-y-1">
            <Label>Request type</Label>
            <Select value={requestType} onValueChange={(v) => setRequestType(v as SparepartRequestType)}>
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="SPAREPART">Sparepart (electric/mechanic)</SelectItem>
                <SelectItem value="CONSUMABLE">Consumable</SelectItem>
                <SelectItem value="SERVICE_EXTERNAL">External service</SelectItem>
              </SelectContent>
            </Select>
          </div>

          {requestType === "SPAREPART" && (
            <div className="space-y-1">
              <Label>Machine id</Label>
              <Input placeholder="Machine UUID" value={machineId} onChange={(e) => setMachineId(e.target.value)} />
            </div>
          )}

          {requestType !== "SERVICE_EXTERNAL" && (
            <div className="space-y-1">
              <Label>Material code (optional — unknown starts PENDING_COMPLETION)</Label>
              <Input
                placeholder="e.g. MC-0001"
                value={materialCode}
                onChange={(e) => setMaterialCode(e.target.value)}
              />
            </div>
          )}

          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1">
              <Label>Quantity</Label>
              <Input type="number" min={1} value={quantity} onChange={(e) => setQuantity(Number(e.target.value))} />
            </div>
            <div className="space-y-1">
              <Label>Est. unit price (optional)</Label>
              <Input type="number" min={0} value={estUnitPrice} onChange={(e) => setEstUnitPrice(e.target.value)} />
            </div>
          </div>

          <div className="space-y-1">
            <Label>Purchase reference URL (optional)</Label>
            <Input placeholder="https://..." value={purchaseUrl} onChange={(e) => setPurchaseUrl(e.target.value)} />
          </div>

          <div className="space-y-1">
            <Label>Notes (optional)</Label>
            <Textarea value={notes} onChange={(e) => setNotes(e.target.value)} />
          </div>

          <div className="flex justify-end gap-2">
            <Button variant="ghost" size="sm" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button size="sm" onClick={handleCreate} disabled={createRequest.isPending || quantity < 1}>
              {createRequest.isPending ? "Creating…" : "Create request"}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
