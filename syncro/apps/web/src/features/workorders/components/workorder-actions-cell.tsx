"use client";

import { useState } from "react";

import { useQuery } from "@tanstack/react-query";
import { FileTextIcon, PrinterIcon, WrenchIcon } from "lucide-react";
import Link from "next/link";

import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { RequestPartDialog } from "@/features/sparepart-requests/components/request-part-dialog";
import { syncroFetch } from "@/lib/api/orval-mutator";

interface WorkOrderReport {
  workOrderId: string;
  reportChronological: string | null;
  reportAnalyze: string | null;
  reportCorrective: string | null;
  reportPreventive: string | null;
}

export function WorkorderActionsCell({ workOrderId }: { workOrderId: string }) {
  const [reportOpen, setReportOpen] = useState(false);

  return (
    <div className="flex items-center gap-1.5">
      <RequestPartDialog workOrderId={workOrderId} />
      <Dialog open={reportOpen} onOpenChange={setReportOpen}>
        <DialogTrigger asChild>
          <Button type="button" variant="secondary" size="sm" className="h-7 px-2 text-xs">
            <FileTextIcon className="mr-1 size-3" />
            Report
          </Button>
        </DialogTrigger>
        <DialogContent className="top-4 max-h-[calc(100svh-2rem)] translate-y-0 overflow-y-auto sm:max-w-2xl">
          <WorkorderReportForm workOrderId={workOrderId} onSaved={() => setReportOpen(false)} />
        </DialogContent>
      </Dialog>
      {/* Story 14-3: WYSIWYG print report route */}
      <Button type="button" variant="outline" size="sm" className="h-7 px-2 text-xs" asChild>
        <Link href={`/dashboard/workorders/${workOrderId}/print`}>
          <PrinterIcon className="mr-1 size-3" />
          Print
        </Link>
      </Button>
    </div>
  );
}

function WorkorderReportForm({ workOrderId, onSaved }: { workOrderId: string; onSaved: () => void }) {
  const { data, isLoading } = useQuery<WorkOrderReport>({
    queryKey: ["/api/v1/workorders", workOrderId, "report"],
    queryFn: async () => {
      const res = await syncroFetch<{ data: WorkOrderReport }>(`/api/v1/workorders/${workOrderId}/report`, {
        method: "GET",
      });
      return res.data;
    },
  });

  const [chronological, setChronological] = useState("");
  const [analyze, setAnalyze] = useState("");
  const [corrective, setCorrective] = useState("");
  const [preventive, setPreventive] = useState("");
  const [saving, setSaving] = useState(false);
  const [loaded, setLoaded] = useState(false);

  if (!loaded && data) {
    setChronological(data.reportChronological ?? "");
    setAnalyze(data.reportAnalyze ?? "");
    setCorrective(data.reportCorrective ?? "");
    setPreventive(data.reportPreventive ?? "");
    setLoaded(true);
  }

  const handleSave = async () => {
    setSaving(true);
    try {
      await syncroFetch(`/api/v1/workorders/${workOrderId}/report`, {
        method: "PUT",
        body: JSON.stringify({
          reportChronological: chronological || null,
          reportAnalyze: analyze || null,
          reportCorrective: corrective || null,
          reportPreventive: preventive || null,
        }),
      });
      onSaved();
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-4">
      <DialogHeader>
        <DialogTitle className="flex items-center gap-2">
          <WrenchIcon className="size-4" />
          Work Order Report — {workOrderId}
        </DialogTitle>
      </DialogHeader>
      {isLoading ? (
        <p className="text-muted-foreground text-sm">Loading report...</p>
      ) : (
        <>
          <div className="space-y-1">
            <Label>Chronological (what happened)</Label>
            <Textarea
              placeholder="Describe the sequence of events..."
              value={chronological}
              onChange={(e) => setChronological(e.target.value)}
              rows={3}
            />
          </div>
          <div className="space-y-1">
            <Label>Analyze (root cause)</Label>
            <Textarea
              placeholder="Root cause analysis..."
              value={analyze}
              onChange={(e) => setAnalyze(e.target.value)}
              rows={3}
            />
          </div>
          <div className="space-y-1">
            <Label>Corrective (what was done)</Label>
            <Textarea
              placeholder="Corrective actions taken..."
              value={corrective}
              onChange={(e) => setCorrective(e.target.value)}
              rows={3}
            />
          </div>
          <div className="space-y-1">
            <Label>Preventive (future prevention)</Label>
            <Textarea
              placeholder="Preventive measures for the future..."
              value={preventive}
              onChange={(e) => setPreventive(e.target.value)}
              rows={3}
            />
          </div>
          <div className="flex justify-end gap-2">
            <Button variant="ghost" size="sm" onClick={onSaved}>
              Cancel
            </Button>
            <Button size="sm" onClick={handleSave} disabled={saving}>
              {saving ? "Saving..." : "Save Report"}
            </Button>
          </div>
        </>
      )}
    </div>
  );
}