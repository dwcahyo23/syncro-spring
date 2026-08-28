"use client";

import { useState } from "react";

import { Loader2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { useApproveRequest } from "@/features/sparepart-requests/hooks/use-sparepart-requests";

/**
 * Story 12-3 approval dialog (FR-142/AD-16): confirm + optional note for approving a
 * pending sparepart request. The button visibility is driven by the backend-provided
 * allowedActions; this dialog only composes the request.
 */
export function ApproveDialog({ requestId }: { requestId: string }) {
  const [open, setOpen] = useState(false);
  const [note, setNote] = useState("");
  const approve = useApproveRequest();

  const handleSubmit = () => {
    approve.mutate(
      { id: requestId, data: note.trim() ? { note: note.trim() } : undefined },
      {
        onSuccess: () => {
          setOpen(false);
          setNote("");
        },
      },
    );
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="outline" size="sm">
          Approve
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Approve request</DialogTitle>
          <DialogDescription>
            Approving moves the request to Acknowledged so inventory can process it.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="approveNote">Note (optional)</Label>
            <Textarea
              id="approveNote"
              placeholder="e.g. Approved for procurement"
              value={note}
              onChange={(e) => setNote(e.target.value)}
              maxLength={500}
              rows={3}
            />
          </div>
          <Button onClick={handleSubmit} disabled={approve.isPending}>
            {approve.isPending ? <Loader2 className="size-4 animate-spin" /> : null}
            Approve
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
