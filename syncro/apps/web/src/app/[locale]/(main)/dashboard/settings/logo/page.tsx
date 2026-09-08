"use client";

import { useState } from "react";

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
      setError(err instanceof Error ? err.message : "Upload failed");
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="space-y-4 p-6">
      <h1 className="font-semibold text-xl">Company Logo</h1>
      <p className="text-muted-foreground text-sm">
        Upload a company logo that appears on printed reports (SUPER_ADMIN only).
      </p>

      <Card className="max-w-md">
        <CardHeader>
          <CardTitle className="text-sm">Current Logo</CardTitle>
        </CardHeader>
        <CardContent>
          {logoLoading ? (
            <p className="text-muted-foreground text-xs">Loading...</p>
          ) : (
            <LogoPreview presignedUrl={logo?.presignedUrl ?? null} />
          )}
        </CardContent>
      </Card>

      <Card className="max-w-md">
        <CardHeader>
          <CardTitle className="text-sm">Replace Logo</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="space-y-1">
            <Label htmlFor="logo-file">Logo image (JPEG, PNG, or WebP, max 5 MB)</Label>
            <Input
              id="logo-file"
              type="file"
              accept="image/jpeg,image/png,image/webp"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
          </div>
          {error && <p className="text-destructive text-xs">{error}</p>}
          <Button onClick={handleUpload} disabled={!file || uploading}>
            {uploading ? "Uploading..." : "Upload Logo"}
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}

function LogoPreview({ presignedUrl }: { presignedUrl: string | null }) {
  if (presignedUrl) {
    return (
      // biome-ignore lint/performance/noImgElement: presigned URL from settings; short-TTL, dynamic
      <img src={presignedUrl} alt="Company logo" className="max-h-24 w-auto" />
    );
  }
  return <p className="text-muted-foreground text-xs">No logo configured.</p>;
}
