"use client";

import { useEffect, useState } from "react";

import { Printer, Upload } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";

import { PrintTemplateView } from "@/components/syncro/print-template-view";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { useReportPrintTemplate } from "@/features/report-templates/hooks/use-report-print-template";
import { useCompanyLogo } from "@/features/settings/hooks/use-company-logo";
import { useWorkorderApprove } from "@/features/workorders/hooks/use-workorder-approve";
import { useWorkorderPrintReport } from "@/features/workorders/hooks/use-workorder-print-report";
import { useWorkorderSignatureUpload } from "@/features/workorders/hooks/use-workorder-signature-upload";
import { isLayoutEmpty, type WorkOrderPlaceholderValues } from "@/lib/print-template";

/**
 * Workorder WYSIWYG print report (story 14-3, FR-175). Tabular aggregate view: WO header,
 * sessions, narrative, CP/CPK, evidence, sparepart requests, and the signature block.
 * Company logo renders at the top (graceful fallback when not configured). Leader/SPV can
 * approve (upload signature image → Garage key → POST /approve). Calls window.print() on
 * mount — the shared print stylesheet (styles/print.css) drives the @media print layout.
 */
export function WorkorderPrintReportPage({ workOrderId, onClose }: { workOrderId: string; onClose: () => void }) {
  const t = useTranslations("workOrders");
  const tc = useTranslations("common");
  const format = useFormatter();
  const { data, isLoading, isError, refetch } = useWorkorderPrintReport(workOrderId);
  const { data: logo } = useCompanyLogo();
  // Global print layout (spec "Template Laporan Cetak"): 404/absent → null →
  // the built-in default below, byte-identical to the pre-change behavior.
  const { data: template, isPending: templatePending } = useReportPrintTemplate("WORK_ORDER");
  const approve = useWorkorderApprove(workOrderId);
  const uploadSignature = useWorkorderSignatureUpload(workOrderId);

  const [signatureKey, setSignatureKey] = useState("");
  const [signerIdentity, setSignerIdentity] = useState("");

  useEffect(() => {
    // Hold the auto-print until the template read settles so the custom layout
    // (or the confirmed default) is what actually hits the paper.
    if (data && !isLoading && !templatePending) {
      const images = Array.from(document.images);
      const ready =
        images.length === 0 ? Promise.resolve() : Promise.all(images.map((img) => img.decode().catch(() => undefined)));
      const timer = setTimeout(() => void ready.then(() => window.print()), 500);
      return () => clearTimeout(timer);
    }
  }, [data, isLoading, templatePending]);

  const canApprove =
    data?.header && (data.header.status === "PENDING_REVIEW" || data.header.status === "CLOSED") && !data.signature;

  const formatInstant = (iso: string) => {
    try {
      return format.dateTime(new Date(iso), { dateStyle: "medium", timeStyle: "short" });
    } catch {
      return iso;
    }
  };

  if (isLoading) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>{t("print.title")}</CardTitle>
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
          <CardTitle>{t("print.title")}</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-muted-foreground text-sm">{t("print.loadFailed")}</p>
          <div className="mt-2 flex gap-2">
            <Button variant="outline" size="sm" onClick={() => void refetch()}>
              {tc("retry")}
            </Button>
            <Button variant="ghost" size="sm" onClick={onClose}>
              {tc("close")}
            </Button>
          </div>
        </CardContent>
      </Card>
    );
  }

  const { header } = data;

  // Placeholder inventory for WORK_ORDER templates — every key mapped from the
  // data this print page already loads (spec's Always block). Missing/blank
  // fields resolve to "—" inside substitutePlaceholders.
  const placeholderValues: WorkOrderPlaceholderValues = {
    nomor: header.id,
    tanggal: header.createdAt ? formatInstant(header.createdAt) : null,
    mesin: header.machineCode ? `${header.machineCode}${header.machineName ? ` · ${header.machineName}` : ""}` : null,
    // P2 (spec review): join only the non-null halves — never print "null ·".
    kategori: [header.categoryCode, header.categoryLabel].filter(Boolean).join(" · ") || null,
    status: t.has(`status.${header.status}`) ? t(`status.${header.status}`) : header.status,
    teknisi: header.assignedTechnicianName,
    deskripsi: header.description,
    kronologis: data.narrative.reportChronological,
    analisis: data.narrative.reportAnalyze,
    korektif: data.narrative.reportCorrective,
    pencegahan: data.narrative.reportPreventive,
    cpk: data.cpk?.cpk ?? null,
    fmea: data.cpk?.fmeaFailureType ?? null,
    stopTime: [data.cpk?.stopTimeReason, data.cpk?.stopTimeDetail].filter(Boolean).join(" — ") || null,
    // Image tokens (leader-signature spec): presigned at print time by our own
    // services; null URL renders as nothing — never "—", never a broken image.
    logo: { imageUrl: logo?.presignedUrl ?? null, alt: t("print.logoAlt"), className: "print-logo" },
    tandaTangan: {
      imageUrl: data.leaderSignature?.signaturePresignedUrl ?? null,
      alt: t("print.signatureAlt"),
      className: "print-signature",
    },
  };
  const customLayout = template && !isLayoutEmpty(template.bodyHtml) ? template.bodyHtml : null;

  /*
   * Non-text blocks — evidence, parts, sessions, signature — are extracted as
   * variables so the DEFAULT layout keeps its historical order (identical to
   * before this feature) while an ACTIVE template always gets them appended
   * below in the amended spec order (2026-09-15): the paper audit trail can
   * never be lost to an admin oversight. Data gates match the old default
   * exactly, and the signature card is unconditional ("Not yet signed" when
   * unsigned).
   */
  const evidenceBlock = data.evidence.length > 0 && (
    <Card className="print-section">
      <CardHeader>
        <CardTitle className="text-sm">{t("print.evidenceTitle")}</CardTitle>
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
  );

  const partsBlock = data.parts.length > 0 && (
    <Card className="print-section">
      <CardHeader>
        <CardTitle className="text-sm">{t("print.partsTitle")}</CardTitle>
      </CardHeader>
      <CardContent>
        <table className="w-full text-xs">
          <thead>
            <tr className="border-b text-left">
              <th className="p-1">{t("print.colMaterial")}</th>
              <th className="p-1">{t("print.colQty")}</th>
              <th className="p-1">{tc("status")}</th>
              <th className="p-1">{tc("notes")}</th>
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
  );

  // Sessions card — historical default position is right under the Header;
  // below an active template it follows Parts per the amended spec order.
  const sessionsBlock =
    data.sessions.length > 0 ? (
      <Card className="print-section">
        <CardHeader>
          <CardTitle className="text-sm">{t("print.sessionsTitle")}</CardTitle>
        </CardHeader>
        <CardContent>
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b text-left">
                <th className="p-1">{t("print.colStart")}</th>
                <th className="p-1">{t("print.colEnd")}</th>
                <th className="p-1">{t("print.colDuration")}</th>
                <th className="p-1">{tc("description")}</th>
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
    ) : null;

  // Signature block — required audit trail, never omitted ("Not yet signed"
  // placeholder card when unsigned). Unconditional by design.
  const signatureBlock = data.signature ? (
    <Card className="print-section">
      <CardHeader>
        <CardTitle className="text-sm">{t("print.approvalTitle")}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-1 text-sm">
        <p>{t("print.signedBy", { identity: data.signature.signerIdentity })}</p>
        <p>{t("print.approvedAt", { time: formatInstant(data.signature.signedAt) })}</p>
        {data.signature.signaturePresignedUrl && (
          // biome-ignore lint/performance/noImgElement: presigned URL from Garage; short-TTL, dynamic
          <img
            src={data.signature.signaturePresignedUrl}
            alt={t("print.signatureAlt")}
            className="print-signature mt-2 max-h-20 border"
          />
        )}
      </CardContent>
    </Card>
  ) : (
    <Card className="print-section">
      <CardHeader>
        <CardTitle className="text-sm">{t("print.approvalTitle")}</CardTitle>
      </CardHeader>
      <CardContent>
        <p className="text-muted-foreground text-sm">{t("print.notYetSigned")}</p>
      </CardContent>
    </Card>
  );

  return (
    <div className="space-y-4">
      <div className="no-print flex items-center justify-between">
        <h1 className="font-semibold text-lg">{t("print.title")}</h1>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={() => window.print()}>
            <Printer className="mr-1 h-3 w-3" />
            {t("print.print")}
          </Button>
          <Button variant="ghost" size="sm" onClick={onClose}>
            {tc("close")}
          </Button>
        </div>
      </div>

      <div className="print-surface space-y-4">
        {customLayout && (
          <>
            <PrintTemplateView html={customLayout} values={placeholderValues} />
            {evidenceBlock}
            {partsBlock}
            {sessionsBlock}
            {signatureBlock}
          </>
        )}
        {!customLayout && (
          <>
            {/* Header */}
            <Card>
              <CardHeader className="flex flex-row items-center justify-between">
                <CardTitle className="text-base">
                  {t.rich("print.headerTitle", {
                    id: header.id,
                    mono: (chunks) => <span className="font-mono">{chunks}</span>,
                  })}
                </CardTitle>
                {logo?.presignedUrl && (
                  // biome-ignore lint/performance/noImgElement: presigned URL from settings; short-TTL, dynamic
                  <img src={logo.presignedUrl} alt={t("print.logoAlt")} className="print-logo h-14 w-auto" />
                )}
              </CardHeader>
              <CardContent className="grid grid-cols-2 gap-2 text-sm">
                <div>
                  {t("print.statusLabel")}{" "}
                  <Badge variant="outline">
                    {t.has(`status.${header.status}`) ? t(`status.${header.status}`) : header.status}
                  </Badge>
                </div>
                <div>
                  {t("print.sourceLabel")} <span className="font-mono">{header.source}</span>
                </div>
                {header.categoryLabel && (
                  <div>
                    {t("print.categoryLabel")}{" "}
                    <span>
                      {header.categoryCode} · {header.categoryLabel}
                    </span>
                  </div>
                )}
                {header.machineCode && (
                  <div>
                    {t("print.machineLabel")}{" "}
                    <span>
                      {header.machineCode}
                      {header.machineName ? ` · ${header.machineName}` : ""}
                    </span>
                  </div>
                )}
                {header.plantCode && (
                  <div>
                    {t("print.plantLabel")} <span className="font-mono">{header.plantCode}</span>
                  </div>
                )}
                {header.assignedTechnicianName && (
                  <div>
                    {t("print.technicianLabel")} <span>{header.assignedTechnicianName}</span>
                  </div>
                )}
                {header.description && (
                  <div className="col-span-2">
                    {t("print.descriptionLabel")} <span>{header.description}</span>
                  </div>
                )}
                {header.doneReason && (
                  <div className="col-span-2">
                    {t("print.doneReasonLabel")} <span>{header.doneReason}</span>
                  </div>
                )}
              </CardContent>
            </Card>

            {/* Sessions — historical default position, right under the header */}
            {sessionsBlock}

            {/* Narrative */}
            <Card className="print-section">
              <CardHeader>
                <CardTitle className="text-sm">{t("print.narrativeTitle")}</CardTitle>
              </CardHeader>
              <CardContent className="space-y-2 text-sm">
                <NarrativeField label={t("print.chronologicalLabel")} value={data.narrative.reportChronological} />
                <NarrativeField label={t("print.analyzeLabel")} value={data.narrative.reportAnalyze} />
                <NarrativeField label={t("print.correctiveLabel")} value={data.narrative.reportCorrective} />
                <NarrativeField label={t("print.preventiveLabel")} value={data.narrative.reportPreventive} />
              </CardContent>
            </Card>

            {/* CP/CPK */}
            {data.cpk && (data.cpk.cpCkLower || data.cpk.cpCkUpper || data.cpk.cpk || data.cpk.cpkPdfPresignedUrl) && (
              <Card className="print-section">
                <CardHeader>
                  <CardTitle className="text-sm">{t("print.cpkTitle")}</CardTitle>
                </CardHeader>
                <CardContent className="grid grid-cols-2 gap-2 text-sm">
                  <div>
                    {t("print.cpLower")} {data.cpk.cpCkLower ?? "—"}
                  </div>
                  <div>
                    {t("print.cpUpper")} {data.cpk.cpCkUpper ?? "—"}
                  </div>
                  <div>
                    {t("print.cpk")} {data.cpk.cpk ?? "—"}
                  </div>
                  {data.cpk.fmeaFailureType && (
                    <div>
                      {t("print.fmea")} {data.cpk.fmeaFailureType}
                    </div>
                  )}
                  {data.cpk.stopTimeReason && (
                    <div>
                      {t("print.stopReason")} {data.cpk.stopTimeReason}
                    </div>
                  )}
                  {data.cpk.stopTimeDetail && (
                    <div className="col-span-2">
                      {t("print.stopDetail")} {data.cpk.stopTimeDetail}
                    </div>
                  )}
                  {data.cpk.cpkPdfPresignedUrl && (
                    <a
                      href={data.cpk.cpkPdfPresignedUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="text-xs underline"
                    >
                      {t("print.cpkPdf")}
                    </a>
                  )}
                </CardContent>
              </Card>
            )}

            {evidenceBlock}
            {partsBlock}
            {signatureBlock}
          </>
        )}

        {/* Approve (leader/SPV, DONE/CLOSED, unsigned) */}
        {canApprove && (
          <Card className="no-print">
            <CardHeader>
              <CardTitle className="text-sm">{t("print.signApproveTitle")}</CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              <div className="space-y-1">
                <Label htmlFor="signature-file" className="text-xs">
                  {t("print.signatureImageLabel")}
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
                placeholder={t("print.objectKeyPlaceholder")}
                value={signatureKey}
                onChange={(e) => setSignatureKey(e.target.value)}
              />
              <Input
                placeholder={t("print.signerPlaceholder")}
                value={signerIdentity}
                onChange={(e) => setSignerIdentity(e.target.value)}
              />
              <Button
                onClick={() =>
                  approve.mutate({ signatureObjectKey: signatureKey.trim(), signerIdentity: signerIdentity || null })
                }
                disabled={approve.isPending || uploadSignature.isPending || !signatureKey.trim()}
              >
                <Upload className="mr-1 h-3 w-3" />
                {approve.isPending ? t("print.approving") : t("print.approve")}
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
      <span className="font-medium">{label}</span> <span>{value ?? "—"}</span>
    </div>
  );
}
