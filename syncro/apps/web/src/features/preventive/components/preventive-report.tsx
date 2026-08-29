"use client";

import { useEffect } from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { usePreventiveReport } from "@/features/preventive/hooks/use-preventive";
import { useCompanyLogo } from "@/features/settings/hooks/use-company-logo";

/**
 * Preventive report print view (story 11-3, FR-133 + story 14-3, FR-175). Data-driven
 * tabular report with checklist items, evidence thumbnails/links, and a signature block.
 * Calls window.print() on mount. Story 14.3 enhances it with the shared print stylesheet
 * (styles/print.css) and the company logo from settings.
 */
export function PreventiveReportPage({ scheduleId, onClose }: { scheduleId: string; onClose: () => void }) {
  const { data, isLoading, isError, refetch } = usePreventiveReport(scheduleId);
  const { data: logo } = useCompanyLogo();

  useEffect(() => {
    document.documentElement.classList.add("print-mode");
    return () => document.documentElement.classList.remove("print-mode");
  }, []);

  useEffect(() => {
    if (data && !isLoading) {
      const images = Array.from(document.images);
      const ready =
        images.length === 0 ? Promise.resolve() : Promise.all(images.map((img) => img.decode().catch(() => undefined)));
      const timer = setTimeout(() => void ready.then(() => window.print()), 500);
      return () => clearTimeout(timer);
    }
  }, [data, isLoading]);

  if (isLoading) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Preventive Report</CardTitle>
        </CardHeader>
        <CardContent>
          <Skeleton className="h-40 w-full" />
        </CardContent>
      </Card>
    );
  }

  if (isError || !data) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Preventive Report</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-muted-foreground text-sm">Failed to load report.</p>
          <div className="mt-2 flex gap-2">
            <Button variant="outline" size="sm" onClick={() => void refetch()}>
              Retry
            </Button>
            <Button variant="ghost" size="sm" onClick={onClose}>
              Close
            </Button>
          </div>
        </CardContent>
      </Card>
    );
  }

  return (
    <div className="space-y-4">
      <div className="no-print flex items-center justify-between">
        <h1 className="font-semibold text-lg">Preventive Report</h1>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={() => window.print()}>
            Print
          </Button>
          <Button variant="ghost" size="sm" onClick={onClose}>
            Close
          </Button>
        </div>
      </div>

      <div className="print-surface space-y-4">
      {/* Header */}
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle className="text-base">{data.programTitle}</CardTitle>
          {logo?.presignedUrl && (
            // biome-ignore lint/performance/noImgElement: presigned URL from settings; short-TTL, dynamic
            <img src={logo.presignedUrl} alt="Company logo" className="print-logo h-14 w-auto" />
          )}
        </CardHeader>
        <CardContent className="grid grid-cols-2 gap-2 text-sm">
          <div>
            Category: <Badge variant="outline">{data.category}</Badge>
          </div>
          <div>
            Schedule: <Badge variant="outline">{data.scheduleType}</Badge>
          </div>
          <div>Due: {data.dueDate}</div>
          <div>
            Status: <Badge variant="outline">{data.scheduleStatus}</Badge>
          </div>
          {data.autoWorkorder && data.workOrderId && (
            <div>
              Workorder: <span className="font-mono">{data.workOrderId}</span>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Checklist items */}
      {data.items.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">Checklist Results</CardTitle>
          </CardHeader>
          <CardContent>
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b text-left">
                  <th className="p-1">#</th>
                  <th className="p-1">Item</th>
                  <th className="p-1">Value</th>
                  <th className="p-1">LSL</th>
                  <th className="p-1">USL</th>
                  <th className="p-1">Note</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((item) => (
                  <tr key={item.position} className="border-b">
                    <td className="p-1">{item.position}</td>
                    <td className="p-1">{item.label}</td>
                    <td className="p-1">{item.value ?? "—"}</td>
                    <td className="p-1">{item.lsl ?? "—"}</td>
                    <td className="p-1">{item.usl ?? "—"}</td>
                    <td className="p-1">{item.note ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {data.notes && <p className="mt-2 text-xs">Notes: {data.notes}</p>}
          </CardContent>
        </Card>
      )}

      {/* Evidence */}
      {data.evidence.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">Evidence</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="flex flex-wrap gap-2">
              {data.evidence.map((att) => (
                <a
                  key={att.id}
                  href={att.presignedUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-xs underline"
                >
                  {att.filename}
                </a>
              ))}
            </div>
          </CardContent>
        </Card>
      )}

      {/* Signature block */}
      {data.signerIdentity && (
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">Approval</CardTitle>
          </CardHeader>
          <CardContent className="space-y-1 text-sm">
            <p>Signed by: {data.signerIdentity}</p>
            <p>Assessment: {data.assessment ?? "—"}</p>
            <p>Approved at: {data.approvedAt ? new Date(data.approvedAt).toLocaleString() : "—"}</p>
            {data.signaturePresignedUrl && (
              <img src={data.signaturePresignedUrl} alt="Signature" className="mt-2 max-h-20 border" />
            )}
          </CardContent>
        </Card>
      )}

      {!data.items.length && !data.signerIdentity && (
        <p className="text-muted-foreground text-sm">No checklist has been submitted for this schedule.</p>
      )}
      </div>
    </div>
  );
}
