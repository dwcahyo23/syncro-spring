"use client";

import { forwardRef, useImperativeHandle, useRef, useState } from "react";

import { useTranslations } from "next-intl";

import { Button } from "@/components/ui/button";

/** Imperative handle: the page's Save button captures the current strokes as a PNG blob. */
export interface SignatureCanvasHandle {
  /** Resolves to null when nothing was drawn. */
  capture: () => Promise<Blob | null>;
}

/**
 * Hand-drawn signature pad (ported from the retired SYNCRO NestJS app's profile canvas).
 * Pointer Events cover mouse/touch/stylus; export is a PNG Blob so the multipart store
 * endpoint (parts contentType/data/password) can take it directly.
 */
export const SignatureCanvas = forwardRef<SignatureCanvasHandle, { disabled?: boolean }>(function SignatureCanvas(
  { disabled = false },
  ref,
) {
  const t = useTranslations("settings.signature");
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [drawing, setDrawing] = useState(false);
  const [hasInk, setHasInk] = useState(false);

  useImperativeHandle(ref, () => ({
    capture: () =>
      new Promise((resolve) => {
        const canvas = canvasRef.current;
        // biome-ignore lint/suspicious/noUnnecessaryConditions: ref.current is null before mount
        if (!canvas || !hasInk) {
          resolve(null);
          return;
        }
        canvas.toBlob((blob) => resolve(blob), "image/png");
      }),
  }));

  const getPoint = (event: React.PointerEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    // biome-ignore lint/suspicious/noUnnecessaryConditions: ref.current is null before mount
    if (!canvas) return null;
    const rect = canvas.getBoundingClientRect();
    // Hidden/zero-sized canvas (tab switched away mid-stroke) → no division by zero.
    if (rect.width === 0 || rect.height === 0) return null;
    return {
      x: (event.clientX - rect.left) * (canvas.width / rect.width),
      y: (event.clientY - rect.top) * (canvas.height / rect.height),
    };
  };

  const draw = (event: React.PointerEvent<HTMLCanvasElement>) => {
    if (!drawing || disabled) return;
    const canvas = canvasRef.current;
    const point = getPoint(event);
    const context = canvas?.getContext("2d");
    if (!point || !context) return;
    context.lineWidth = 2;
    context.lineCap = "round";
    context.strokeStyle = "#111827";
    context.lineTo(point.x, point.y);
    context.stroke();
    setHasInk(true);
  };

  const start = (event: React.PointerEvent<HTMLCanvasElement>) => {
    if (disabled) return;
    const canvas = canvasRef.current;
    const point = getPoint(event);
    const context = canvas?.getContext("2d");
    if (!point || !context) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    context.beginPath();
    context.moveTo(point.x, point.y);
    setDrawing(true);
  };

  const clear = () => {
    const canvas = canvasRef.current;
    const context = canvas?.getContext("2d");
    // `canvas?.` already short-circuits the unmounted case; context is undefined then.
    if (!context || !canvas) return;
    context.clearRect(0, 0, canvas.width, canvas.height);
    setHasInk(false);
  };

  return (
    <div className="space-y-3">
      <canvas
        ref={canvasRef}
        width={480}
        height={180}
        aria-label={t("canvasLabel")}
        className="h-44 w-full max-w-md touch-none rounded-md border bg-white disabled:opacity-60"
        aria-disabled={disabled}
        onPointerDown={start}
        onPointerMove={draw}
        onPointerUp={() => setDrawing(false)}
        onPointerCancel={() => setDrawing(false)}
        onPointerLeave={() => setDrawing(false)}
      />
      <div className="flex gap-2">
        <Button type="button" variant="outline" onClick={clear} disabled={disabled || !hasInk}>
          {t("clear")}
        </Button>
      </div>
    </div>
  );
});
