"use client";

import { useState } from "react";

import { Loader2 } from "lucide-react";
import { useTranslations } from "next-intl";

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
  const t = useTranslations("sparepartRequests");
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
          {t("approve.trigger")}
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("approve.title")}</DialogTitle>
          <DialogDescription>{t("approve.description")}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="approveNote">{t("approve.noteLabel")}</Label>
            <Textarea
              id="approveNote"
              placeholder={t("approve.notePlaceholder")}
              value={note}
              onChange={(e) => setNote(e.target.value)}
              maxLength={500}
              rows={3}
            />
          </div>
          <Button onClick={handleSubmit} disabled={approve.isPending}>
            {approve.isPending ? <Loader2 className="size-4 animate-spin" /> : null}
            {t("approve.trigger")}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
