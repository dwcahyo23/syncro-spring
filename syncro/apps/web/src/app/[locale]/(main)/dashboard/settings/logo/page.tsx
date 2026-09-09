"use client";

import { useState } from "react";

import { useTranslations } from "next-intl";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useCompanyLogo } from "@/features/settings/hooks/use-company-logo";
import { syncroFetch } from "@/lib/api/orval-mutator";

/**
 * Logo upload page (story 14-3, FR-175). SUPER_ADMIN only — the backend gate returns 403
 * for other roles. Shows the current logo preview and a file upload to replace it.
 */
export default function LogoPage() {
  const t = useTranslations("settings.logo");
  const tc = useTranslations("common");
  const { data: logo, isLoading: logoLoading, refetch } = useCompanyLogo();
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleUpload = async () => {
    if (!file) return;
    setUploading(true);
    setError(null);
    try {
      const formData = new FormData();
      formData.append("filename", file.name);
      formData.append("contentType", file.type);
      formData.append("data", file);
      await syncroFetch("/api/v1/settings/logo", {
        method: "PUT",
        body: formData,
        headers: {},
      });
      await refetch();
      setFile(null);
    } catch (err) {
      // Backend messages are contract data (rendered as-is); the fallback is translated.
      setError(err instanceof Error ? err.message : t("uploadFailed"));
    } finally {
      setUploading(false);
    }
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
          {logoLoading ? (
            <p className="text-muted-foreground text-xs">{tc("loading")}</p>
          ) : (
            <LogoPreview presignedUrl={logo?.presignedUrl ?? null} alt={t("alt")} empty={t("noLogo")} />
          )}
        </CardContent>
      </Card>

      <Card className="max-w-md">
        <CardHeader>
          <CardTitle className="text-sm">{t("replaceTitle")}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="space-y-1">
            <Label htmlFor="logo-file">{t("fileLabel")}</Label>
            <Input
              id="logo-file"
              type="file"
              accept="image/jpeg,image/png,image/webp"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
          </div>
          {error && <p className="text-destructive text-xs">{error}</p>}
          <Button onClick={handleUpload} disabled={!file || uploading}>
            {uploading ? t("uploading") : t("upload")}
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}

function LogoPreview({ presignedUrl, alt, empty }: { presignedUrl: string | null; alt: string; empty: string }) {
  if (presignedUrl) {
    return (
      // biome-ignore lint/performance/noImgElement: presigned URL from settings; short-TTL, dynamic
      <img src={presignedUrl} alt={alt} className="max-h-24 w-auto" />
    );
  }
  return <p className="text-muted-foreground text-xs">{empty}</p>;
}
