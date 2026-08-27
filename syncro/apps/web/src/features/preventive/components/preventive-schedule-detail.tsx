"use client";

import { useState } from "react";

import { Printer, Trash2, Upload } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import { PreventiveReportPage } from "@/features/preventive/components/preventive-report";
import {
  useApproveSchedule,
  useChecklist,
  useDeleteEvidence,
  useEvidenceList,
  useSkipSchedule,
  useSubmitChecklist,
  useUploadEvidence,
} from "@/features/preventive/hooks/use-preventive";
import type { PreventiveScheduleView } from "@/features/preventive/types";

/**
 * Schedule detail/completion panel (story 11-2, FR-132). Checklist item editor,
 * evidence upload/list, and leader-only approve/skip actions. Loading/empty/error/
 * read-only/forbidden states handled inline.
 */
export function PreventiveScheduleDetail({
  schedule,
  onClose,
}: {
  schedule: PreventiveScheduleView;
  onClose: () => void;
}) {
  const { data: checklist, isLoading: checklistLoading, isError: checklistError } = useChecklist(schedule.id);
  const { data: evidence, isLoading: evidenceLoading, isError: evidenceError } = useEvidenceList(schedule.id);
  const submitChecklist = useSubmitChecklist();
  const approveSchedule = useApproveSchedule();
  const skipSchedule = useSkipSchedule();
  const uploadEvidence = useUploadEvidence();
  const deleteEvidence = useDeleteEvidence();

  const [items, setItems] = useState<{ label: string; value: string; lsl: string; usl: string; note: string }[]>([
    { label: "", value: "", lsl: "", usl: "", note: "" },
  ]);
  const [notes, setNotes] = useState("");
  const [signatureKey, setSignatureKey] = useState("");
  const [signerIdentity, setSignerIdentity] = useState("");
  const [assessment, setAssessment] = useState("");

  const isSubmitted = schedule.checklistStatus === "SUBMITTED"; // submitted but not yet approved
  const isApproved = schedule.checklistStatus === "APPROVED";
  const isTerminal = schedule.status === "PERFORMED" || schedule.status === "SKIPPED";
  const canSubmit = !isSubmitted && !isApproved && !isTerminal; // SCHEDULED or IN_PROGRESS
  const canAmend = isSubmitted && !isTerminal; // IN_PROGRESS, submitted but not approved
  const canApprove = isSubmitted && !isApproved && !isTerminal;
  const canSkip = !isTerminal && !isApproved;
  const [showReport, setShowReport] = useState(false);

  if (checklistLoading) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Schedule Detail</CardTitle>
        </CardHeader>
        <CardContent>
          <Skeleton className="h-20 w-full" />
        </CardContent>
      </Card>
    );
  }

  if (checklistError) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Schedule Detail</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-muted-foreground text-sm">Failed to load checklist.</p>
        </CardContent>
      </Card>
    );
  }

  if (showReport) {
    return <PreventiveReportPage scheduleId={schedule.id} onClose={() => setShowReport(false)} />;
  }

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle className="text-base">
          {schedule.dueDate} · {schedule.category} · {schedule.scheduleType}
        </CardTitle>
        <div className="flex items-center gap-2">
          {schedule.derivedStatus === "OVERDUE" ? (
            <Badge variant="destructive">Overdue</Badge>
          ) : (
            <Badge variant="outline">{schedule.derivedStatus}</Badge>
          )}
          <Badge variant="secondary">{schedule.checklistStatus}</Badge>
          {isApproved && (
            <Button variant="outline" size="sm" onClick={() => setShowReport(true)}>
              <Printer className="mr-1 h-3 w-3" />
              Report
            </Button>
          )}
          <Button variant="ghost" size="sm" onClick={onClose}>
            Close
          </Button>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* Checklist items — editable when not submitted yet OR when amending a submitted checklist */}
        {(canSubmit || canAmend) && (
          <div className="space-y-2">
            <Label>Checklist Items</Label>
            {items.map((item, i) => (
              <div key={i} className="flex flex-wrap gap-2">
                <Input
                  placeholder="Label"
                  className="w-40"
                  value={item.label}
                  onChange={(e) => {
                    const next = [...items];
                    next[i] = { ...next[i], label: e.target.value };
                    setItems(next);
                  }}
                />
                <Input
                  placeholder="Value"
                  className="w-28"
                  value={item.value}
                  onChange={(e) => {
                    const next = [...items];
                    next[i] = { ...next[i], value: e.target.value };
                    setItems(next);
                  }}
                />
                <Input
                  placeholder="LSL"
                  className="w-20"
                  value={item.lsl}
                  onChange={(e) => {
                    const next = [...items];
                    next[i] = { ...next[i], lsl: e.target.value };
                    setItems(next);
                  }}
                />
                <Input
                  placeholder="USL"
                  className="w-20"
                  value={item.usl}
                  onChange={(e) => {
                    const next = [...items];
                    next[i] = { ...next[i], usl: e.target.value };
                    setItems(next);
                  }}
                />
                <Button variant="ghost" size="icon" onClick={() => setItems(items.filter((_, j) => j !== i))}>
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            ))}
            <Button
              variant="outline"
              size="sm"
              onClick={() => setItems([...items, { label: "", value: "", lsl: "", usl: "", note: "" }])}
            >
              Add Item
            </Button>
          </div>
        )}

        {(canSubmit || canAmend) && (
          <div className="space-y-2">
            <Label htmlFor="notes">Notes</Label>
            <Textarea
              id="notes"
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              placeholder="Optional notes"
            />
          </div>
        )}

        {(canSubmit || canAmend) && (
          <Button
            onClick={() => {
              const filledItems = items.filter((i) => i.label.trim());
              if (filledItems.length === 0) return;
              submitChecklist.mutate({
                scheduleId: schedule.id,
                data: {
                  notes: notes || null,
                  items: filledItems.map((i) => ({
                    label: i.label,
                    value: i.value || null,
                    lsl: i.lsl || null,
                    usl: i.usl || null,
                    note: i.note || null,
                  })),
                },
                isAmend: isSubmitted,
              });
            }}
            disabled={submitChecklist.isPending || items.filter((i) => i.label.trim()).length === 0}
          >
            {isSubmitted ? "Amend Checklist" : "Submit Checklist"}
          </Button>
        )}

        {/* Evidence */}
        <div className="space-y-2">
          <Label>Evidence</Label>
          {evidenceLoading ? (
            <Skeleton className="h-10 w-full" />
          ) : evidenceError ? (
            <p className="text-muted-foreground text-xs">Failed to load evidence.</p>
          ) : (
            <div className="space-y-1">
              {(evidence ?? []).map((att) => (
                <div key={att.id} className="flex items-center justify-between gap-2 rounded border p-2">
                  <a href={att.presignedUrl} target="_blank" rel="noopener noreferrer" className="text-xs underline">
                    {att.filename}
                  </a>
                  <Button
                    variant="ghost"
                    size="icon"
                    onClick={() => deleteEvidence.mutate({ scheduleId: schedule.id, attachmentId: att.id })}
                  >
                    <Trash2 className="h-3 w-3" />
                  </Button>
                </div>
              ))}
            </div>
          )}
          {!isTerminal && (
            <Dialog>
              <DialogTrigger asChild>
                <Button variant="outline" size="sm">
                  <Upload className="mr-1 h-3 w-3" />
                  Upload Evidence
                </Button>
              </DialogTrigger>
              <DialogContent className="top-4 max-h-[calc(100svh-2rem)] translate-y-0 overflow-y-auto sm:max-w-2xl">
                <DialogHeader>
                  <DialogTitle>Upload Evidence</DialogTitle>
                </DialogHeader>
                <Input
                  type="file"
                  accept="image/jpeg,image/png,image/webp,application/pdf"
                  onChange={(e) => {
                    const file = e.target.files?.[0];
                    if (file) {
                      uploadEvidence.mutate({ scheduleId: schedule.id, file });
                    }
                  }}
                />
              </DialogContent>
            </Dialog>
          )}
        </div>

        {/* Leader approve */}
        {canApprove && (
          <div className="space-y-2 rounded border p-3">
            <Label className="font-semibold">Leader Approval</Label>
            <Label htmlFor="signature-file" className="text-xs">
              Signature image (uploaded as evidence, key auto-filled)
            </Label>
            <Input
              id="signature-file"
              type="file"
              accept="image/jpeg,image/png,image/webp"
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) {
                  const sig = { scheduleId: schedule.id, file };
                  uploadEvidence.mutate(sig, {
                    onSuccess: (att) => setSignatureKey(att.objectKey),
                  });
                }
              }}
            />
            <Label htmlFor="signature-key" className="text-xs">
              Signature object key (from Garage)
            </Label>
            <Input
              id="signature-key"
              placeholder="Signature object key (auto-filled on upload)"
              value={signatureKey}
              onChange={(e) => setSignatureKey(e.target.value)}
            />
            <Input
              placeholder="Signer identity (defaults to your name)"
              value={signerIdentity}
              onChange={(e) => setSignerIdentity(e.target.value)}
            />
            <Textarea
              placeholder="Assessment (optional)"
              value={assessment}
              onChange={(e) => setAssessment(e.target.value)}
            />
            <Button
              onClick={() => {
                approveSchedule.mutate({
                  scheduleId: schedule.id,
                  data: {
                    signatureObjectKey: signatureKey,
                    signerIdentity: signerIdentity || null,
                    assessment: assessment || null,
                  },
                });
              }}
              disabled={approveSchedule.isPending || !signatureKey.trim()}
            >
              Approve
            </Button>
          </div>
        )}

        {canSkip && (
          <Button variant="outline" onClick={() => skipSchedule.mutate(schedule.id)} disabled={skipSchedule.isPending}>
            Skip
          </Button>
        )}

        {/* Submitted checklist read-only */}
        {checklist?.result && (
          <div className="space-y-1 rounded border p-3">
            <Label>Submitted Checklist</Label>
            {checklist.result.items.map((item, i) => (
              <div key={i} className="text-xs">
                {item.label}: {item.value ?? "—"} {item.lsl ? `[${item.lsl}–${item.usl ?? "—"}]` : ""}
              </div>
            ))}
            {checklist.result.notes && <p className="text-muted-foreground text-xs">Notes: {checklist.result.notes}</p>}
            {checklist.result.leaderId && (
              <p className="text-muted-foreground text-xs">
                Approved by {checklist.result.signerIdentity ?? checklist.result.leaderId} at{" "}
                {checklist.result.approvedAt}
              </p>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
