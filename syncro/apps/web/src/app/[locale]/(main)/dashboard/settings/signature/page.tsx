"use client";

import { useRef, useState } from "react";

import { useTranslations } from "next-intl";

import { SignatureCanvas, type SignatureCanvasHandle } from "@/components/syncro/signature-canvas";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useMySignature, useStoreMySignature } from "@/features/settings/hooks/use-user-signature";
import { SyncroApiError } from "@/lib/api/orval-mutator";
import { expireAuthSession } from "@/lib/auth/auth-client";

/**
 * Signature settings page (leader-signature spec). Every user draws their signature
 * once — password-verified, stored in Garage — so completed reports can stamp it via
 * the {{tandaTangan}} template token. Mirrors the logo page's direct-URL convention.
 */
export default function SignaturePage() {
  const t = useTranslations("settings.signature");
  const { data: stored, isLoading, refetch } = useMySignature();
  const store = useStoreMySignature();

  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const canvasRef = useRef<SignatureCanvasHandle>(null);

  const handleSave = async () => {
    if (!password) return;
    setError(null);
    const blob = await canvasRef.current?.capture();
    if (!blob) {
      setError(t("nothingDrawn"));
      return;
    }
    store.mutate(
      { blob, password },
      {
        onSuccess: () => {
          setPassword("");
          void refetch();
        },
        onError: (err) => {
          if (!(err instanceof SyncroApiError)) {
            setError(t("saveFailed"));
            return;
          }
          // 401 INVALID_CREDENTIALS = wrong signature password; any other 401 is an
          // expired session (syncroFetch would have expired it; raw fetch must too).
          const code = (err.payload as { code?: string } | undefined)?.code;
          if (err.status === 401 && code === "INVALID_CREDENTIALS") setError(t("passwordWrong"));
          else if (err.status === 401) expireAuthSession();
          else if (err.status === 423) setError(t("blocked"));
          else setError(t("saveFailed"));
        },
      },
    );
  };

  return (
    <div className="space-y-4 p-6">
      <h1 className="font-semibold text-xl">{t("title")}</h1>
      <p className="text-muted-foreground text-sm">{t("subtitle")}</p>

      <Card className="max-w-md">
        <CardHeader>
          <CardTitle className="text-sm">{t("currentTitle")}</CardTitle>
        </CardHeader>
        <CardContent>
          <CurrentSignature
            isLoading={isLoading}
            presignedUrl={stored?.presignedUrl ?? null}
            alt={t("alt")}
            noneLabel={t("noneYet")}
            loadingLabel={t("loading")}
          />
        </CardContent>
      </Card>

      <Card className="max-w-md">
        <CardHeader>
          <CardTitle className="text-sm">{stored ? t("replaceTitle") : t("createTitle")}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <SignatureCanvas ref={canvasRef} disabled={store.isPending} />
          <div className="space-y-1">
            <Label htmlFor="signature-password">{t("passwordLabel")}</Label>
            <Input
              id="signature-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
            />
          </div>
          <p className="text-muted-foreground text-xs">{t("passwordHelp")}</p>
          {error && <p className="text-destructive text-xs">{error}</p>}
          <Button onClick={handleSave} disabled={store.isPending || !password}>
            {store.isPending ? t("saving") : t("save")}
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}

function CurrentSignature({
  isLoading,
  presignedUrl,
  alt,
  noneLabel,
  loadingLabel,
}: {
  isLoading: boolean;
  presignedUrl: string | null;
  alt: string;
  noneLabel: string;
  loadingLabel: string;
}) {
  if (isLoading) return <p className="text-muted-foreground text-xs">{loadingLabel}</p>;
  if (presignedUrl)
    return (
      // biome-ignore lint/performance/noImgElement: presigned URL from Garage; short-TTL, dynamic
      <img src={presignedUrl} alt={alt} className="max-h-20 w-auto border" />
    );
  return <p className="text-muted-foreground text-xs">{noneLabel}</p>;
}
