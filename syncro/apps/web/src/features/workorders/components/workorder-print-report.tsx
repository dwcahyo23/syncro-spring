"use client";

import { useEffect, useState } from "react";

import { Printer, Upload } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { useCompanyLogo } from "@/features/settings/hooks/use-company-logo";
import { useWorkorderApprove } from "@/features/workorders/hooks/use-workorder-approve";
import { useWorkorderPrintReport } from "@/features/workorders/hooks/use-workorder-print-report";
import { useWorkorderSignatureUpload } from "@/features/workorders/hooks/use-workorder-signature-upload";

/**
 * Workorder WYSIWYG print report (story 14-3, FR-175). Tabular aggregate view: WO header,
 * sessions, narrative, CP/CPK, evidence, sparepart requests, and the signature block.
 * Company logo renders at the top (graceful fallback when not configured). Leader/SPV can
 * approve (upload signature image → Garage key → POST /approve). Calls window.print() on
 * mount — the shared print stylesheet (styles/print.css) drives the @media print layout.
 */
export function WorkorderPrintReportPage({
  workOrderId,
  onClose,
}: {
  workOrderId: string;
  onClose: () => void;
}) {
  const { data, isLoading, isError, refetch } = useWorkorderPrintReport(workOrderId);
  const { data: logo } = useCompanyLogo();
  const approve = useWorkorderApprove(workOrderId);
  const uploadSignature = useWorkorderSignatureUpload(workOrderId);

  const [signatureKey, setSignatureKey] = useState("");
  const [signerIdentity, setSignerIdentity] = useState("");

  useEffect(() => {
    if (data && !isLoading) {
      const images = Array.from(document.images);
      const ready =
        images.length === 0 ? Promise.resolve() : Promise.all(images.map((img) => img.decode().catch(() => undefined)));
      const timer = setTimeout(() => void ready.then(() => window.print()), 500);
      return () => clearTimeout(timer);
    }
  }, [data, isLoading]);

  const canApprove = data?.header && (data.header.status === "DONE" || data.header.status === "CLOSED") && !data.signature;

  if (isLoading) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Work Order Report</CardTitle>
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
          <CardTitle>Work Order Report</CardTitle>
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

  const { header } = data;

  return (
    <div className="space-y-4">
      <div className="no-print flex items-center justify-between">
        <h1 className="font-semibold text-lg">Work Order Report</h1>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={() => window.print()}>
            <Printer className="mr-1 h-3 w-3" />
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
            <CardTitle className="text-base">
              Work Order Report — <span className="font-mono">{header.id}</span>
            </CardTitle>
            {logo?.presignedUrl && (
              // biome-ignore lint/performance/noImgElement: presigned URL from settings; short-TTL, dynamic
              <img src={logo.presignedUrl} alt="Company logo" className="print-logo h-14 w-auto" />
            )}
          </CardHeader>
          <CardContent className="grid grid-cols-2 gap-2 text-sm">
            <div>
              Status: <Badge variant="outline">{header.status}</Badge>
            </div>
            <div>
              Source: <span className="font-mono">{header.source}</span>
            </div>
            {header.categoryLabel && (
              <div>
                Category: <span>{header.categoryCode} · {header.categoryLabel}</span>
              </div>
            )}
            {header.machineCode && (
              <div>
                Machine: <span>{header.machineCode}{header.machineName ? ` · ${header.machineName}` : ""}</span>
              </div>
            )}
            {header.plantCode && (
              <div>
                Plant: <span className="font-mono">{header.plantCode}</span>
              </div>
            )}
            {header.assignedTechnicianName && (
              <div>
                Technician: <span>{header.assignedTechnicianName}</span>
              </div>
            )}
            {header.description && (
              <div className="col-span-2">
                Description: <span>{header.description}</span>
              </div>
            )}
            {header.doneReason && (
              <div className="col-span-2">
                Done reason: <span>{header.doneReason}</span>
              </div>
            )}
          </CardContent>
        </Card>

        {/* Sessions */}
        {data.sessions.length > 0 && (
          <Card className="print-section">
            <CardHeader>
              <CardTitle className="text-sm">Repair Sessions</CardTitle>
            </CardHeader>
            <CardContent>
              <table className="w-full text-xs">
                <thead>
                  <tr className="border-b text-left">
                    <th className="p-1">Start</th>
                    <th className="p-1">End</th>
                    <th className="p-1">Duration (min)</th>
                    <th className="p-1">Description</th>
                  </tr>
                </thead>
                <tbody>
                  {data.sessions.map((session) => (
                    <tr key={session.id} className="border-b">
                      <td className="p-1">{formatInstant(session.startedAt)}</td>
                      <td className="p-1">{session.endedAt ? formatInstant(session.endedAt) : "—"}</td>
                      <td className="p-1">{session.durationMinutes ?? "—"}</td>
                      <td className="p-1">{session.description ?? "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </CardContent>
          </Card>
        )}

        {/* Narrative */}
        <Card className="print-section">
          <CardHeader>
            <CardTitle className="text-sm">Report Narrative</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 text-sm">
            <NarrativeField label="Chronological" value={data.narrative.reportChronological} />
            <NarrativeField label="Analyze" value={data.narrative.reportAnalyze} />
            <NarrativeField label="Corrective" value={data.narrative.reportCorrective} />
            <NarrativeField label="Preventive" value={data.narrative.reportPreventive} />
          </CardContent>
        </Card>

        {/* CP/CPK */}
        {data.cpk && (data.cpk.cpCkLower || data.cpk.cpCkUpper || data.cpk.cpk || data.cpk.cpkPdfPresignedUrl) && (
          <Card className="print-section">
            <CardHeader>
              <CardTitle className="text-sm">Capability (CP/CPK)</CardTitle>
            </CardHeader>
            <CardContent className="grid grid-cols-2 gap-2 text-sm">
              <div>CP lower: {data.cpk.cpCkLower ?? "—"}</div>
              <div>CP upper: {data.cpk.cpCkUpper ?? "—"}</div>
              <div>CPK: {data.cpk.cpk ?? "—"}</div>
              {data.cpk.fmeaFailureType && <div>FMEA: {data.cpk.fmeaFailureType}</div>}
              {data.cpk.stopTimeReason && <div>Stop reason: {data.cpk.stopTimeReason}</div>}
              {data.cpk.stopTimeDetail && <div className="col-span-2">Stop detail: {data.cpk.stopTimeDetail}</div>}
              {data.cpk.cpkPdfPresignedUrl && (
                <a href={data.cpk.cpkPdfPresignedUrl} target="_blank" rel="noopener noreferrer" className="text-xs underline">
                  Capability PDF
                </a>
              )}
            </CardContent>
          </Card>
        )}

        {/* Evidence */}
        {data.evidence.length > 0 && (
          <Card className="print-section">
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

        {/* Sparepart requests */}
        {data.parts.length > 0 && (
          <Card className="print-section">
            <CardHeader>
              <CardTitle className="text-sm">Sparepart Requests</CardTitle>
            </CardHeader>
            <CardContent>
              <table className="w-full text-xs">
                <thead>
                  <tr className="border-b text-left">
                    <th className="p-1">Material</th>
                    <th className="p-1">Qty</th>
                    <th className="p-1">Status</th>
                    <th className="p-1">Notes</th>
                  </tr>
                </thead>
                <tbody>
                  {data.parts.map((part) => (
                    <tr key={part.id} className="border-b">
                      <td className="p-1 font-mono">{part.materialCode ?? "—"}</td>
                      <td className="p-1">{part.quantity}</td>
                      <td className="p-1">{part.status}</td>
                      <td className="p-1">{part.notes ?? "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </CardContent>
          </Card>
        )}

        {/* Signature block */}
        {data.signature ? (
          <Card className="print-section">
            <CardHeader>
              <CardTitle className="text-sm">Approval</CardTitle>
            </CardHeader>
            <CardContent className="space-y-1 text-sm">
              <p>Signed by: {data.signature.signerIdentity}</p>
              <p>Approved at: {formatInstant(data.signature.signedAt)}</p>
              {data.signature.signaturePresignedUrl && (
                // biome-ignore lint/performance/noImgElement: presigned URL from Garage; short-TTL, dynamic
                <img src={data.signature.signaturePresignedUrl} alt="Signature" className="print-signature mt-2 max-h-20 border" />
              )}
            </CardContent>
          </Card>
        ) : (
          <Card className="print-section">
            <CardHeader>
              <CardTitle className="text-sm">Approval</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-muted-foreground text-sm">Not yet signed.</p>
            </CardContent>
          </Card>
        )}

        {/* Approve (leader/SPV, DONE/CLOSED, unsigned) */}
        {canApprove && (
          <Card className="no-print">
            <CardHeader>
              <CardTitle className="text-sm">Sign &amp; Approve</CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              <div className="space-y-1">
                <Label htmlFor="signature-file" className="text-xs">
                  Signature image (uploaded to Garage; key auto-filled)
                </Label>
                <Input
                  id="signature-file"
                  type="file"
                  accept="image/jpeg,image/png,image/webp"
                  onChange={(e) => {
                    const file = e.target.files?.[0];
                    if (file) {
                      uploadSignature.mutate(file, {
                        onSuccess: (att) => setSignatureKey(att.objectKey),
                      });
                    }
                  }}
                />
              </div>
              <Input
                placeholder="Signature object key (auto-filled on upload)"
                value={signatureKey}
                onChange={(e) => setSignatureKey(e.target.value)}
              />
              <Input
                placeholder="Signer identity (defaults to your name)"
                value={signerIdentity}
                onChange={(e) => setSignerIdentity(e.target.value)}
              />
              <Button
                onClick={() => approve.mutate({ signatureObjectKey: signatureKey.trim(), signerIdentity: signerIdentity || null })}
                disabled={approve.isPending || uploadSignature.isPending || !signatureKey.trim()}
              >
                <Upload className="mr-1 h-3 w-3" />
                {approve.isPending ? "Approving..." : "Approve"}
              </Button>
            </CardContent>
          </Card>
        )}
      </div>
    </div>
  );
}

function NarrativeField({ label, value }: { label: string; value: string | null }) {
  return (
    <div>
      <span className="font-medium">{label}:</span> <span>{value ?? "—"}</span>
    </div>
  );
}

function formatInstant(iso: string) {
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}
