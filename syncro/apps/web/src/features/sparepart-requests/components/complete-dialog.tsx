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
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useCompleteRequest } from "@/features/sparepart-requests/hooks/use-sparepart-requests";

/**
 * Story 12-4 completion dialog (FR-144): registers the material code (and optional
 * image object key / est-price id) for a PENDING_COMPLETION request. The button
 * visibility is driven by the backend-provided allowedActions; this dialog only
 * composes the request. Non-native shadcn controls only.
 */
export function CompleteDialog({ requestId }: { requestId: string }) {
  const t = useTranslations("sparepartRequests");
  const [open, setOpen] = useState(false);
  const [materialCode, setMaterialCode] = useState("");
  const [imageObjectKey, setImageObjectKey] = useState("");
  const complete = useCompleteRequest();

  const handleSubmit = () => {
    if (!materialCode.trim()) return;
    complete.mutate(
      {
        id: requestId,
        data: {
          materialCode: materialCode.trim(),
          imageObjectKey: imageObjectKey.trim() ? imageObjectKey.trim() : null,
        },
      },
      {
        onSuccess: () => {
          setOpen(false);
          setMaterialCode("");
          setImageObjectKey("");
        },
      },
    );
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="outline" size="sm">
          {t("complete.trigger")}
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("complete.title")}</DialogTitle>
          <DialogDescription>{t("complete.description")}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="materialCode">{t("complete.materialCodeLabel")}</Label>
            <Input
              id="materialCode"
              placeholder={t("complete.materialCodePlaceholder")}
              value={materialCode}
              onChange={(e) => setMaterialCode(e.target.value)}
              maxLength={64}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="imageObjectKey">{t("complete.imageLabel")}</Label>
            <Input
              id="imageObjectKey"
              placeholder={t("complete.imagePlaceholder")}
              value={imageObjectKey}
              onChange={(e) => setImageObjectKey(e.target.value)}
              maxLength={255}
            />
          </div>
          <Button onClick={handleSubmit} disabled={complete.isPending || !materialCode.trim()}>
            {complete.isPending ? <Loader2 className="size-4 animate-spin" /> : null}
            {t("complete.submit")}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
