"use client";

import type React from "react";
import { useRef } from "react";

import { Loader2Icon, Trash2Icon, UploadIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";

export interface SparepartImageUploadProps {
  /** Short-TTL presigned URL of the current image; null when none is stored. */
  value: string | null;
  onUpload: (file: File) => void;
  onRemove: () => void;
  isUploading?: boolean;
  isRemoving?: boolean;
  error?: string;
  readOnly?: boolean;
  disabledReason?: string;
}

/**
 * Controlled single-image upload/preview for a sparepart (Story 8-4). The preview
 * always comes from a backend-minted presigned URL (never persisted bytes); upload
 * and remove are delegated to the parent, which owns the mutation and error mapping.
 */
export function SparepartImageUpload({
  value,
  onUpload,
  onRemove,
  isUploading = false,
  isRemoving = false,
  error,
  readOnly = false,
  disabledReason,
}: SparepartImageUploadProps) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  // biome-ignore lint/nursery/useNullishCoalescing: boolean OR of two flags; ?? would treat false as a value to skip
  const busy = isUploading || isRemoving;

  function pickFile(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (file) {
      onUpload(file);
    }
  }

  return (
    <div className="grid gap-2">
      <Label>Image</Label>
      {value ? (
        // Presigned URLs are minted per upload and expire, so next/image cannot
        // be configured with a static remote host; a plain <img> is required.
        // biome-ignore lint/performance/noImgElement: dynamic short-TTL presigned URL
        <img
          src={value}
          alt="Sparepart preview"
          className="max-h-48 w-full rounded-md border object-contain"
          data-testid="sparepart-image-preview"
        />
      ) : (
        <p className="py-2 text-muted-foreground text-sm" data-testid="sparepart-image-empty">
          No image uploaded yet.
        </p>
      )}
      {readOnly ? (
        <p className="text-muted-foreground text-xs">{disabledReason ?? "View only."}</p>
      ) : (
        <>
          <div className="flex items-center gap-2">
            <input
              ref={fileInputRef}
              type="file"
              accept="image/*"
              className="hidden"
              data-testid="sparepart-image-input"
              disabled={busy}
              onChange={pickFile}
            />
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={busy}
              onClick={() => fileInputRef.current?.click()}
            >
              {isUploading ? <Loader2Icon className="animate-spin" /> : <UploadIcon />}
              {value ? "Replace image" : "Upload image"}
            </Button>
            {value ? (
              <Button type="button" variant="destructive" size="sm" disabled={busy} onClick={onRemove}>
                {isRemoving ? <Loader2Icon className="animate-spin" /> : <Trash2Icon />}
                Remove
              </Button>
            ) : null}
          </div>
          <p className="text-muted-foreground text-xs">
            JPEG, PNG, WebP, or GIF up to 5 MB. Backend limits are authoritative.
          </p>
        </>
      )}
      {error ? (
        <p role="alert" className="text-destructive text-sm">
          {error}
        </p>
      ) : null}
    </div>
  );
}
