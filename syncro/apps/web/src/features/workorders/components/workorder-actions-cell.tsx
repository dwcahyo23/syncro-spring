"use client";

import { useState } from "react";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ChevronDownIcon,
  FileTextIcon,
  PrinterIcon,
  UserCheckIcon,
  WrenchIcon,
} from "lucide-react";
import Link from "next/link";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import type { UserMasterView } from "@/features/organization/types";
import { RequestPartDialog } from "@/features/sparepart-requests/components/request-part-dialog";
import { syncroFetch } from "@/lib/api/orval-mutator";

interface WorkOrderReport {
  workOrderId: string;
  reportChronological: string | null;
  reportAnalyze: string | null;
  reportCorrective: string | null;
  reportPreventive: string | null;
}

/**
 * Workorder lifecycle next-states (AD-4 state machine), excluding ON_PROCUREMENT:
 * procurement placement belongs to the inventory domain.
 */
const NEXT_STATUSES: Record<string, string[]> = {
  DRAFT: ["OPEN"],
  OPEN: [],
  ASSIGNED: ["IN_PROGRESS"],
  IN_PROGRESS: ["DONE"],
  DONE: ["CLOSED"],
};

const STATUS_LABELS: Record<string, string> = {
  OPEN: "Open",
  ASSIGNED: "Assign",
  IN_PROGRESS: "In Progress",
  ON_PROCUREMENT: "On Procurement",
  DONE: "Done",
  CLOSED: "Close",
  CANCELLED: "Cancel",
};

export function WorkorderActionsCell({
  workOrderId,
  status,
  assignedTechnicianName,
  assignableUsers,
  isLoadingUsers,
}: {
  workOrderId: string;
  status: string;
  assignedTechnicianName?: string | null;
  assignableUsers: UserMasterView[];
  isLoadingUsers: boolean;
}) {
  const [assignOpen, setAssignOpen] = useState(false);
  const [reportOpen, setReportOpen] = useState(false);
  const [requestPartOpen, setRequestPartOpen] = useState(false);
  const [transitionOpen, setTransitionOpen] = useState(false);
  const [transitionTo, setTransitionTo] = useState("");

  const nextStatuses = NEXT_STATUSES[status] ?? [];

  const handleTransition = (toStatus: string) => {
    setTransitionTo(toStatus);
    setTransitionOpen(true);
  };

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button type="button" variant="ghost" size="sm" className="h-7 px-2 text-xs">
            Actions
            <ChevronDownIcon className="ml-1 size-3" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-44">
          <DropdownMenuLabel className="text-muted-foreground text-xs font-normal">
            {status}
            {assignedTechnicianName ? ` · ${assignedTechnicianName}` : ""}
          </DropdownMenuLabel>
          {status === "OPEN" && (
            <DropdownMenuItem onSelect={() => setAssignOpen(true)}>
              <UserCheckIcon className="size-3.5" />
              Assign
            </DropdownMenuItem>
          )}
          {nextStatuses.map((next) => (
            <DropdownMenuItem key={next} onSelect={() => handleTransition(next)}>
              <WrenchIcon className="size-3.5" />
              {STATUS_LABELS[next] ?? next}
            </DropdownMenuItem>
          ))}
          <DropdownMenuSeparator />
          <DropdownMenuItem onSelect={() => setRequestPartOpen(true)}>
            <WrenchIcon className="size-3.5" />
            Request Part
          </DropdownMenuItem>
          <DropdownMenuItem onSelect={() => setReportOpen(true)}>
            <FileTextIcon className="size-3.5" />
            Report
          </DropdownMenuItem>
          <DropdownMenuItem asChild>
            <Link href={`/dashboard/workorders/${workOrderId}/print`}>
              <PrinterIcon className="size-3.5" />
              Print
            </Link>
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>

      {/* Dialogs — controlled from outside, no nested triggers */}
      {status === "OPEN" && (
        <AssignDialog
          workOrderId={workOrderId}
          open={assignOpen}
          onOpenChange={setAssignOpen}
          assignableUsers={assignableUsers}
          isLoadingUsers={isLoadingUsers}
        />
      )}
      {transitionTo && (
        <TransitionDialog
          workOrderId={workOrderId}
          toStatus={transitionTo}
          open={transitionOpen}
          onOpenChange={(o) => { setTransitionOpen(o); if (!o) setTransitionTo(""); }}
        />
      )}
      <RequestPartDialog workOrderId={workOrderId} open={requestPartOpen} onOpenChange={setRequestPartOpen} />
      <ReportDialog workOrderId={workOrderId} open={reportOpen} onOpenChange={setReportOpen} />
    </>
  );
}

function AssignDialog({
  workOrderId,
  open,
  onOpenChange,
  assignableUsers,
  isLoadingUsers,
}: {
  workOrderId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  assignableUsers: UserMasterView[];
  isLoadingUsers: boolean;
}) {
  const queryClient = useQueryClient();
  const [assigneeId, setAssigneeId] = useState("");

  const assignMutation = useMutation({
    mutationFn: async (userId: string) => {
      const response = await syncroFetch<{ data: { id: string } }>(`/api/v1/workorders/${workOrderId}/assign`, {
        method: "POST",
        body: JSON.stringify({ assigneeUserId: userId }),
      });
      return response.data;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["/api/v1/workorders"] });
      void queryClient.invalidateQueries({ queryKey: ["/api/v1/workorders/kanban"] });
      toast.success("Workorder assigned");
      onOpenChange(false);
      setAssigneeId("");
    },
    onError: () => {
      toast.error("Failed to assign workorder");
    },
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <UserCheckIcon className="size-4" />
            Assign Technician — {workOrderId}
          </DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-1">
            <Label>Technician</Label>
            <Select value={assigneeId} onValueChange={setAssigneeId} disabled={isLoadingUsers}>
              <SelectTrigger aria-label="Assign technician" disabled={isLoadingUsers}>
                <SelectValue placeholder={isLoadingUsers ? "Loading..." : "Select technician"} />
              </SelectTrigger>
              <SelectContent>
                {assignableUsers.map((u) => (
                  <SelectItem key={u.id} value={u.id}>
                    {u.displayName ?? u.loginIdentifier} ({u.applicationRole})
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="flex justify-end gap-2">
            <Button variant="ghost" size="sm" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button size="sm" disabled={!assigneeId || assignMutation.isPending} onClick={() => assignMutation.mutate(assigneeId)}>
              <UserCheckIcon className="mr-1 size-3" />
              {assignMutation.isPending ? "Assigning..." : "Assign"}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function TransitionDialog({ workOrderId, toStatus, open, onOpenChange }: { workOrderId: string; toStatus: string; open: boolean; onOpenChange: (open: boolean) => void }) {
  const queryClient = useQueryClient();
  const [reason, setReason] = useState("");

  const transitionMutation = useMutation({
    mutationFn: async () => {
      const response = await syncroFetch<{ data: { id: string } }>(`/api/v1/workorders/${workOrderId}/transition`, {
        method: "POST",
        body: JSON.stringify({ toStatus, reason: reason || undefined }),
      });
      return response.data;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["/api/v1/workorders"] });
      void queryClient.invalidateQueries({ queryKey: ["/api/v1/workorders/kanban"] });
      toast.success(`Workorder moved to ${STATUS_LABELS[toStatus] ?? toStatus}`);
      onOpenChange(false);
      setReason("");
    },
    onError: () => {
      toast.error("Failed to update workorder status");
    },
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <WrenchIcon className="size-4" />
            Move to {STATUS_LABELS[toStatus] ?? toStatus} — {workOrderId}
          </DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-1">
            <Label>Reason (optional)</Label>
            <Textarea
              placeholder="Reason for this status change..."
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              rows={3}
            />
          </div>
          <div className="flex justify-end gap-2">
            <Button variant="ghost" size="sm" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button size="sm" disabled={transitionMutation.isPending} onClick={() => transitionMutation.mutate()}>
              <WrenchIcon className="mr-1 size-3" />
              {transitionMutation.isPending ? "Updating..." : `Move to ${STATUS_LABELS[toStatus] ?? toStatus}`}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function ReportDialog({ workOrderId, open, onOpenChange }: { workOrderId: string; open: boolean; onOpenChange: (open: boolean) => void }) {
  const { data, isLoading } = useQuery<WorkOrderReport>({
    queryKey: ["/api/v1/workorders", workOrderId, "report"],
    queryFn: async () => {
      const res = await syncroFetch<{ data: WorkOrderReport }>(`/api/v1/workorders/${workOrderId}/report`, {
        method: "GET",
      });
      return res.data;
    },
    enabled: open,
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
      onOpenChange(false);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="top-4 max-h-[calc(100svh-2rem)] translate-y-0 overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <WrenchIcon className="size-4" />
            Work Order Report — {workOrderId}
          </DialogTitle>
        </DialogHeader>
        {isLoading ? (
          <p className="text-muted-foreground text-sm">Loading report...</p>
        ) : (
          <div className="space-y-4">
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
              <Button variant="ghost" size="sm" onClick={() => onOpenChange(false)}>
                Cancel
              </Button>
              <Button size="sm" onClick={handleSave} disabled={saving}>
                {saving ? "Saving..." : "Save Report"}
              </Button>
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}