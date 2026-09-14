"use client";

import { useMemo, useState } from "react";

import Link from "next/link";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  CalendarIcon,
  ChevronDownIcon,
  EyeIcon,
  FileTextIcon,
  PlusIcon,
  PrinterIcon,
  UserCheckIcon,
  WrenchIcon,
} from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Calendar } from "@/components/ui/calendar";
import { Checkbox } from "@/components/ui/checkbox";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import type { UserMasterView } from "@/features/organization/types";
import { RequestPartDialog } from "@/features/sparepart-requests/components/request-part-dialog";
import type { WorkLogStoppedReason, WorkLogView } from "@/features/workorders/types";
import { syncroFetch } from "@/lib/api/orval-mutator";
import { useCalendarLocale } from "@/lib/i18n/format";

interface WorkOrderReport {
  workOrderId: string;
  reportChronological: string | null;
  reportAnalyze: string | null;
  reportCorrective: string | null;
  reportPreventive: string | null;
}

interface WorkAssignment {
  id: string;
  workOrderId: string;
  technicianId: string;
  isActive: boolean;
}

/** One work-log form row state for a selected technician session. */
interface WorkLogDraft {
  startDate: Date | undefined;
  startHour: string;
  startMinute: string;
  endDate: Date | undefined;
  endHour: string;
  endMinute: string;
  stoppedReason: WorkLogStoppedReason | "";
  activityNote: string;
  completionNote: string;
  error: string | null;
}

/** Per-technician draft: existing active assignment id (prefill) + N work sessions. */
interface TechDraft {
  assignmentId: string | null;
  sessions: WorkLogDraft[];
}

function emptyDraft(): WorkLogDraft {
  const now = new Date();
  return {
    startDate: undefined,
    startHour: String(now.getHours()).padStart(2, "0"),
    startMinute: String(now.getMinutes()).padStart(2, "0"),
    endDate: undefined,
    endHour: String(now.getHours()).padStart(2, "0"),
    endMinute: String(now.getMinutes()).padStart(2, "0"),
    stoppedReason: "",
    activityNote: "",
    completionNote: "",
    error: null,
  };
}

function hasDraftData(draft: WorkLogDraft): boolean {
  return (
    draft.startDate !== undefined ||
    draft.endDate !== undefined ||
    draft.activityNote.trim() !== "" ||
    draft.stoppedReason !== "" ||
    draft.completionNote.trim() !== ""
  );
}
// Mirrors WorkOrderStateMachine's valid edges. OPEN→IN_PROGRESS is not listed here:
// the transition endpoint rejects it — assignment (Tugaskan & Kerjakan) owns it.
const NEXT_STATUSES: Record<string, string[]> = {
  IN_PROGRESS: ["PENDING_SPAREPART", "PENDING_REVIEW", "CANCELLED"],
  PENDING_SPAREPART: ["IN_PROGRESS"],
  PENDING_REVIEW: ["CLOSED"],
};

/** Source-aware transition labels: same target, different verb depending on where we come from. */
const TRANSITION_LABELS: Record<string, string> = {
  "IN_PROGRESS->PENDING_SPAREPART": "PENDING_SPAREPART",
  "PENDING_SPAREPART->IN_PROGRESS": "RESUME",
  "IN_PROGRESS->PENDING_REVIEW": "PENDING_REVIEW",
  "PENDING_REVIEW->CLOSED": "CLOSED",
  "IN_PROGRESS->CANCELLED": "CANCELLED",
};

const STOPPED_REASONS: WorkLogStoppedReason[] = ["WAITING_SPAREPART", "SHIFT_END", "COMPLETED", "OTHER"];

const HOURS = Array.from({ length: 24 }, (_, i) => String(i).padStart(2, "0"));
const MINUTES = Array.from({ length: 60 }, (_, i) => String(i).padStart(2, "0"));

export function WorkorderActionsCell({
  workOrderId,
  status,
  assignedTechnicianName,
  assignableUsers,
  isLoadingUsers,
  createdAt,
  hideDetail = false,
}: {
  workOrderId: string;
  status: string;
  assignedTechnicianName?: string | null;
  assignableUsers: UserMasterView[];
  isLoadingUsers: boolean;
  createdAt?: string | null;
  /** True on the detail page itself, where navigating to the detail is pointless. */
  hideDetail?: boolean;
}) {
  const t = useTranslations("workOrders");
  const tc = useTranslations("common");
  const [assignOpen, setAssignOpen] = useState(false);
  const [reportOpen, setReportOpen] = useState(false);
  const [requestPartOpen, setRequestPartOpen] = useState(false);
  const [transitionOpen, setTransitionOpen] = useState(false);
  const [transitionTo, setTransitionTo] = useState("");

  const nextStatuses = NEXT_STATUSES[status] ?? [];
  const statusLabel = (s: string) => (t.has(`status.${s}`) ? t(`status.${s}`) : s);
  // Edge-aware: "PENDING_SPAREPART→IN_PROGRESS" reads "Lanjutkan Pengerjaan", not "Mulai".
  const transitionLabel = (from: string, to: string) => {
    const edge = TRANSITION_LABELS[`${from}->${to}`];
    const key = `transition.${edge ?? to}`;
    return t.has(key) ? t(key) : statusLabel(to);
  };

  const handleTransition = (toStatus: string) => {
    setTransitionTo(toStatus);
    setTransitionOpen(true);
  };

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button type="button" variant="ghost" size="sm" className="h-7 px-2 text-xs">
            {tc("actions")}
            <ChevronDownIcon className="ml-1 size-3" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-44">
          <DropdownMenuLabel className="font-normal text-muted-foreground text-xs">
            {statusLabel(status)}
            {assignedTechnicianName ? ` · ${assignedTechnicianName}` : ""}
          </DropdownMenuLabel>
          {(status === "OPEN" || status === "IN_PROGRESS" || status === "PENDING_SPAREPART") && (
            <DropdownMenuItem onSelect={() => setAssignOpen(true)}>
              <UserCheckIcon className="size-3.5" />
              {t("actionsCell.menu.assignWork")}
            </DropdownMenuItem>
          )}
          {nextStatuses.map((next) => (
            <DropdownMenuItem key={next} onSelect={() => handleTransition(next)}>
              <WrenchIcon className="size-3.5" />
              {transitionLabel(status, next)}
            </DropdownMenuItem>
          ))}
          <DropdownMenuSeparator />
          {!hideDetail && (
            <DropdownMenuItem asChild>
              <Link href={`/dashboard/workorders/${workOrderId}`}>
                <EyeIcon className="size-3.5" />
                {t("actionsCell.menu.detail")}
              </Link>
            </DropdownMenuItem>
          )}
          <DropdownMenuItem onSelect={() => setRequestPartOpen(true)}>
            <WrenchIcon className="size-3.5" />
            {t("actionsCell.menu.requestPart")}
          </DropdownMenuItem>
          <DropdownMenuItem onSelect={() => setReportOpen(true)}>
            <FileTextIcon className="size-3.5" />
            {t("actionsCell.menu.report")}
          </DropdownMenuItem>
          <DropdownMenuItem asChild>
            <Link href={`/dashboard/workorders/${workOrderId}/print`}>
              <PrinterIcon className="size-3.5" />
              {t("actionsCell.menu.print")}
            </Link>
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>

      {(status === "OPEN" || status === "IN_PROGRESS" || status === "PENDING_SPAREPART") && (
        <AssignWorkDialog
          workOrderId={workOrderId}
          createdAt={createdAt}
          open={assignOpen}
          onOpenChange={setAssignOpen}
          assignableUsers={assignableUsers}
          isLoadingUsers={isLoadingUsers}
        />
      )}
      {transitionTo && (
        <TransitionDialog
          workOrderId={workOrderId}
          fromStatus={status}
          toStatus={transitionTo}
          open={transitionOpen}
          onOpenChange={(o) => {
            setTransitionOpen(o);
            if (!o) setTransitionTo("");
          }}
        />
      )}
      <RequestPartDialog workOrderId={workOrderId} open={requestPartOpen} onOpenChange={setRequestPartOpen} />
      <ReportDialog workOrderId={workOrderId} open={reportOpen} onOpenChange={setReportOpen} />
    </>
  );
}

/**
 * Assign & Work dialog (17-6, reworked 2026-09-14 for the backfill workflow):
 * multi-technician assignment with ACTIVE assignments prefilled from GET /assignments
 * (no duplicate POST → no 409 on a second visit), and N work sessions per technician
 * (08–10 + 14–16 + tomorrow, all backdatable within created_at). New assignments post
 * first; every work log references its assignment id (17-1/17-2). Exported for reuse
 * by the workorder detail page.
 */
export function AssignWorkDialog({
  workOrderId,
  createdAt,
  open,
  onOpenChange,
  assignableUsers,
  isLoadingUsers,
}: {
  workOrderId: string;
  createdAt?: string | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  assignableUsers: UserMasterView[];
  isLoadingUsers: boolean;
}) {
  const t = useTranslations("workOrders");
  const tc = useTranslations("common");
  const format = useFormatter();
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState<Record<string, TechDraft>>({});
  const [listError, setListError] = useState<string | null>(null);
  const [techSearch, setTechSearch] = useState("");

  const rawMin = createdAt ? new Date(createdAt) : undefined;
  const minDate = rawMin && !Number.isNaN(rawMin.getTime()) ? rawMin : undefined;

  // Active assignments: prefill the tick list so a second visit adds sessions only.
  const { data: assignmentsRes, isFetching: isLoadingAssignments } = useQuery<WorkAssignment[]>({
    queryKey: ["/api/v1/workorders", workOrderId, "assignments"],
    queryFn: async () => {
      const res = await syncroFetch<{ data: WorkAssignment[] }>(`/api/v1/workorders/${workOrderId}/assignments`, {
        method: "GET",
      });
      return res.data;
    },
    enabled: open,
  });

  const activeAssignmentByTech = useMemo(() => {
    const map = new Map<string, string>();
    for (const a of assignmentsRes ?? []) {
      if (a.isActive) map.set(a.technicianId, a.id);
    }
    return map;
  }, [assignmentsRes]);

  // Previously logged sessions: shown read-only per tech so re-opening the dialog to
  // add an update never hides what was already recorded (leader feedback 2026-09-14).
  const { data: workLogsRes } = useQuery<WorkLogView[]>({
    queryKey: ["/api/v1/workorders", workOrderId, "work-logs"],
    queryFn: async () => {
      const res = await syncroFetch<{ data: WorkLogView[] }>(`/api/v1/workorders/${workOrderId}/work-logs`, {
        method: "GET",
      });
      return res.data;
    },
    enabled: open,
  });

  const logsByAssignmentId = useMemo(() => {
    const map = new Map<string, WorkLogView[]>();
    for (const log of workLogsRes ?? []) {
      if (!log.workAssignmentId) continue;
      const list = map.get(log.workAssignmentId) ?? [];
      list.push(log);
      map.set(log.workAssignmentId, list);
    }
    for (const list of map.values()) list.sort((a, b) => a.startTime.localeCompare(b.startTime));
    return map;
  }, [workLogsRes]);

  // Reset state when dialog closes
  const handleOpenChange = (o: boolean) => {
    if (!o) {
      setSelected({});
      setListError(null);
    }
    onOpenChange(o);
  };

  const selectedIds = Object.keys(selected);

  const toggleTechnician = (userId: string) => {
    setSelected((prev) => {
      const next = { ...prev };
      if (userId in next) {
        delete next[userId];
      } else {
        next[userId] = { assignmentId: activeAssignmentByTech.get(userId) ?? null, sessions: [emptyDraft()] };
      }
      return next;
    });
  };

  const addSession = (userId: string) => {
    setSelected((prev) => ({
      ...prev,
      [userId]: { ...prev[userId], sessions: [...prev[userId].sessions, emptyDraft()] },
    }));
  };

  const removeSession = (userId: string, index: number) => {
    setSelected((prev) => {
      const tech = prev[userId];
      if (!tech || tech.sessions.length <= 1) return prev;
      return { ...prev, [userId]: { ...tech, sessions: tech.sessions.filter((_, i) => i !== index) } };
    });
  };

  const updateDraft = (userId: string, sessionIndex: number, patch: Partial<WorkLogDraft>) => {
    setSelected((prev) => {
      const tech = prev[userId];
      const sessions = tech.sessions.map((s, i) => (i === sessionIndex ? { ...s, ...patch, error: null } : s));
      return { ...prev, [userId]: { ...tech, sessions } };
    });
  };

  const submitMutation = useMutation({
    mutationFn: async () => {
      // Validate every non-empty session. "CODE:userId:sessionIndex" sentinels are
      // control-flow keys — keep them raw.
      for (const userId of selectedIds) {
        const tech = selected[userId];
        for (let i = 0; i < tech.sessions.length; i++) {
          const draft = tech.sessions[i];
          if (!hasDraftData(draft)) continue;
          if (draft.activityNote.trim() === "") throw new Error(`ACTIVITY_NOTE:${userId}:${i}`);
          if (!draft.startDate) throw new Error(`START_REQUIRED:${userId}:${i}`);
          const startIso = toIso(draft.startDate, draft.startHour, draft.startMinute);
          if (minDate && startIso < minDate.toISOString()) throw new Error(`BACKDATE:${userId}:${i}`);
          if (draft.endDate) {
            const endIso = toIso(draft.endDate, draft.endHour, draft.endMinute);
            if (endIso <= startIso) throw new Error(`END_BEFORE_START:${userId}:${i}`);
          }
        }
      }

      // New assignments only — prefill ones reuse their existing id (no duplicate 409).
      const assignmentIds: Record<string, string> = {};
      for (const userId of selectedIds) {
        const existing = selected[userId].assignmentId;
        if (existing) {
          assignmentIds[userId] = existing;
          continue;
        }
        const res = await syncroFetch<{ data: WorkAssignment }>(`/api/v1/workorders/${workOrderId}/assignments`, {
          method: "POST",
          body: JSON.stringify({ technicianId: userId }),
        });
        assignmentIds[userId] = res.data.id;
        // Persist immediately: if a later work-log POST fails and the user hits Save
        // again, the retry must reuse this id — a second POST /assignments 409s.
        setSelected((prev) => ({ ...prev, [userId]: { ...prev[userId], assignmentId: res.data.id } }));
      }

      for (const userId of selectedIds) {
        for (const draft of selected[userId].sessions) {
          if (!hasDraftData(draft)) continue;
          const startTime = toIso(draft.startDate as Date, draft.startHour, draft.startMinute);
          const endTime = draft.endDate ? toIso(draft.endDate, draft.endHour, draft.endMinute) : null;
          await syncroFetch(`/api/v1/workorders/${workOrderId}/work-logs`, {
            method: "POST",
            body: JSON.stringify({
              workAssignmentId: assignmentIds[userId],
              startTime,
              endTime,
              stoppedReason: draft.stoppedReason || null,
              activityNote: draft.activityNote.trim(),
              completionNote: draft.completionNote.trim() || undefined,
            }),
          });
        }
      }
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["/api/v1/workorders"] });
      void queryClient.invalidateQueries({ queryKey: ["/api/v1/workorders/kanban"] });
      void queryClient.invalidateQueries({ queryKey: ["/api/v1/workorders", workOrderId, "assignments"] });
      void queryClient.invalidateQueries({ queryKey: ["/api/v1/workorders", workOrderId, "work-logs"] });
      toast.success(t("actionsCell.assignDialog.saved"));
      onOpenChange(false);
      setSelected({});
      setListError(null);
    },
    onError: (error: Error) => {
      // Clear stale per-session errors on each submission attempt
      setSelected((prev) => {
        const next: Record<string, TechDraft> = {};
        for (const [userId, tech] of Object.entries(prev)) {
          next[userId] = { ...tech, sessions: tech.sessions.map((s) => ({ ...s, error: null })) };
        }
        return next;
      });
      for (const code of ["ACTIVITY_NOTE", "START_REQUIRED", "BACKDATE", "END_BEFORE_START"] as const) {
        const prefix = `${code}:`;
        if (!error.message.startsWith(prefix)) continue;
        const [, userId, index] = error.message.slice(prefix.length).split(":");
        const messageKey = {
          ACTIVITY_NOTE: "activityNote",
          START_REQUIRED: "startRequired",
          BACKDATE: "backdate",
          END_BEFORE_START: "endBeforeStart",
        }[code] as "activityNote" | "startRequired" | "backdate" | "endBeforeStart";
        setSelected((prev) => {
          const tech = prev[userId];
          if (!tech) return prev;
          const sessions = tech.sessions.map((s, i) =>
            i === Number(index) ? { ...s, error: t(`actionsCell.assignDialog.errors.${messageKey}`) } : s,
          );
          return { ...prev, [userId]: { ...tech, sessions } };
        });
        return;
      }
      setListError(t("actionsCell.assignDialog.errors.failed"));
    },
  });

  const canSubmit = selectedIds.length > 0 && !submitMutation.isPending;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="top-4 max-h-[calc(100svh-2rem)] translate-y-0 overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <UserCheckIcon className="size-4" />
            {t("actionsCell.assignDialog.title", { id: workOrderId })}
          </DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          {isLoadingUsers || isLoadingAssignments ? (
            <p className="text-muted-foreground text-sm">{t("actionsCell.assignDialog.loadingTechnicians")}</p>
          ) : (
            <div className="space-y-2">
              <Label>{t("actionsCell.assignDialog.technicians")}</Label>
              {assignableUsers.length > 8 ? (
                <Input
                  value={techSearch}
                  onChange={(e) => setTechSearch(e.target.value)}
                  placeholder={tc("search")}
                  aria-label={tc("search")}
                />
              ) : null}
              <div className="max-h-48 space-y-1 overflow-y-auto rounded-md border p-2">
                {assignableUsers.length === 0 ? (
                  <p className="text-muted-foreground text-sm">{t("actionsCell.assignDialog.noAssignable")}</p>
                ) : (
                  assignableUsers
                    .filter((u) => {
                      const term = techSearch.trim().toLowerCase();
                      if (!term) return true;
                      return `${u.displayName ?? ""} ${u.loginIdentifier ?? ""}`.toLowerCase().includes(term);
                    })
                    .map((u) => {
                      const checked = u.id in selected;
                      const alreadyActive = activeAssignmentByTech.has(u.id);
                      return (
                        <div
                          key={u.id}
                          className="flex cursor-pointer items-center gap-2 rounded px-2 py-1 text-sm hover:bg-muted"
                        >
                          <Checkbox
                            checked={checked}
                            onCheckedChange={() => toggleTechnician(u.id)}
                            aria-label={t("actionsCell.assignDialog.selectAria", {
                              name: u.displayName ?? u.loginIdentifier ?? "",
                            })}
                          />
                          <span>
                            {u.displayName ?? u.loginIdentifier}{" "}
                            <span className="text-muted-foreground">({u.applicationRole})</span>
                            {alreadyActive ? (
                              <Badge variant="secondary" className="ml-2 text-xs">
                                {t("actionsCell.assignDialog.assignedBadge")}
                              </Badge>
                            ) : null}
                          </span>
                        </div>
                      );
                    })
                )}
              </div>
            </div>
          )}

          {selectedIds.map((userId) => {
            const tech = selected[userId];
            const user = assignableUsers.find((u) => u.id === userId);
            const label = user?.displayName ?? user?.loginIdentifier ?? userId;
            // Past logged sessions on this assignment, shown read-only above the new ones.
            const pastSessions = tech.assignmentId ? (logsByAssignmentId.get(tech.assignmentId) ?? []) : [];
            return (
              <div key={userId} className="space-y-3 rounded-md border p-3">
                <div className="flex items-center justify-between">
                  <Label className="font-medium">{label}</Label>
                  <span className="text-muted-foreground text-xs">
                    {tech.assignmentId
                      ? t("actionsCell.assignDialog.workLog")
                      : t("actionsCell.assignDialog.newAssignment")}
                  </span>
                </div>
                {pastSessions.length > 0 ? (
                  <div className="space-y-1">
                    <p className="font-medium text-muted-foreground text-xs">
                      {t("actionsCell.assignDialog.pastSessions")}
                    </p>
                    {pastSessions.map((log) => (
                      <div key={log.id} className="rounded-md border border-dashed p-2 text-xs">
                        <p>
                          {format.dateTime(new Date(log.startTime), {
                            day: "numeric",
                            month: "short",
                            hour: "2-digit",
                            minute: "2-digit",
                            hour12: false,
                          })}
                          {" → "}
                          {log.endTime
                            ? format.dateTime(new Date(log.endTime), {
                                day: "numeric",
                                month: "short",
                                hour: "2-digit",
                                minute: "2-digit",
                                hour12: false,
                              })
                            : t("detail.openSession")}
                          {log.stoppedReason ? (
                            <Badge variant="secondary" className="ml-1 text-xs">
                              {t.has(`stoppedReason.${log.stoppedReason}`)
                                ? t(`stoppedReason.${log.stoppedReason}`)
                                : log.stoppedReason}
                            </Badge>
                          ) : null}
                        </p>
                        <p className="text-muted-foreground">{log.activityNote}</p>
                      </div>
                    ))}
                  </div>
                ) : null}
                {tech.sessions.map((draft, sessionIndex) => (
                  <div key={sessionIndex} className="space-y-2 rounded-md border bg-muted/30 p-2">
                    <div className="flex items-center justify-between">
                      <span className="font-medium text-xs">
                        {t("actionsCell.assignDialog.sessionN", { n: pastSessions.length + sessionIndex + 1 })}
                      </span>
                      {tech.sessions.length > 1 ? (
                        <Button
                          type="button"
                          variant="ghost"
                          size="sm"
                          className="h-6 px-2 text-xs"
                          onClick={() => removeSession(userId, sessionIndex)}
                        >
                          {t("actionsCell.assignDialog.removeSession")}
                        </Button>
                      ) : null}
                    </div>
                    <div className="grid grid-cols-2 gap-2">
                      <DateTimePicker
                        label={t("actionsCell.assignDialog.start")}
                        date={draft.startDate}
                        hour={draft.startHour}
                        minute={draft.startMinute}
                        minDate={minDate}
                        onDateChange={(d) => updateDraft(userId, sessionIndex, { startDate: d })}
                        onHourChange={(h) => updateDraft(userId, sessionIndex, { startHour: h })}
                        onMinuteChange={(m) => updateDraft(userId, sessionIndex, { startMinute: m })}
                      />
                      <DateTimePicker
                        label={t("actionsCell.assignDialog.end")}
                        date={draft.endDate}
                        hour={draft.endHour}
                        minute={draft.endMinute}
                        minDate={minDate}
                        onDateChange={(d) => updateDraft(userId, sessionIndex, { endDate: d })}
                        onHourChange={(h) => updateDraft(userId, sessionIndex, { endHour: h })}
                        onMinuteChange={(m) => updateDraft(userId, sessionIndex, { endMinute: m })}
                      />
                    </div>
                    <div className="space-y-1">
                      <Label>{t("actionsCell.assignDialog.stoppedReason")}</Label>
                      <Select
                        value={draft.stoppedReason || "none"}
                        onValueChange={(v) =>
                          updateDraft(userId, sessionIndex, {
                            stoppedReason: v === "none" ? "" : (v as WorkLogStoppedReason),
                          })
                        }
                      >
                        <SelectTrigger aria-label={t("actionsCell.assignDialog.stoppedReason")}>
                          <SelectValue placeholder={t("actionsCell.assignDialog.optional")} />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem value="none">{t("stoppedReason.NONE")}</SelectItem>
                          {STOPPED_REASONS.map((r) => (
                            <SelectItem key={r} value={r}>
                              {t.has(`stoppedReason.${r}`) ? t(`stoppedReason.${r}`) : r}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                    <div className="space-y-1">
                      <Label>{t("actionsCell.assignDialog.activityNote")}</Label>
                      <Textarea
                        placeholder={t("actionsCell.assignDialog.activityPlaceholder")}
                        value={draft.activityNote}
                        onChange={(e) => updateDraft(userId, sessionIndex, { activityNote: e.target.value })}
                        rows={2}
                      />
                    </div>
                    <div className="space-y-1">
                      <Label>{t("actionsCell.assignDialog.completionNote")}</Label>
                      <Input
                        placeholder={t("actionsCell.assignDialog.optional")}
                        value={draft.completionNote}
                        onChange={(e) => updateDraft(userId, sessionIndex, { completionNote: e.target.value })}
                      />
                    </div>
                    {draft.error ? <p className="text-destructive text-xs">{draft.error}</p> : null}
                  </div>
                ))}
                <Button type="button" variant="outline" size="sm" onClick={() => addSession(userId)}>
                  <PlusIcon className="mr-1 size-3" />
                  {t("actionsCell.assignDialog.addSession")}
                </Button>
              </div>
            );
          })}

          {listError ? <p className="text-destructive text-sm">{listError}</p> : null}
          {minDate ? (
            <p className="text-muted-foreground text-xs">
              {t("actionsCell.assignDialog.backdateNote", {
                datetime: format.dateTime(minDate, {
                  day: "numeric",
                  month: "short",
                  year: "numeric",
                  hour: "2-digit",
                  minute: "2-digit",
                  hour12: false,
                }),
              })}
            </p>
          ) : null}

          <div className="flex justify-end gap-2">
            <Button variant="ghost" size="sm" onClick={() => onOpenChange(false)}>
              {tc("cancel")}
            </Button>
            <Button size="sm" disabled={!canSubmit} onClick={() => submitMutation.mutate()}>
              <UserCheckIcon className="mr-1 size-3" />
              {submitMutation.isPending ? tc("saving") : t("actionsCell.assignDialog.save")}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** Non-native date + hour/minute picker composed from shadcn Calendar + Select. */
function DateTimePicker({
  label,
  date,
  hour,
  minute,
  minDate,
  onDateChange,
  onHourChange,
  onMinuteChange,
}: {
  label: string;
  date: Date | undefined;
  hour: string;
  minute: string;
  minDate?: Date;
  onDateChange: (d: Date | undefined) => void;
  onHourChange: (h: string) => void;
  onMinuteChange: (m: string) => void;
}) {
  const t = useTranslations("workOrders");
  const format = useFormatter();
  const calendarLocale = useCalendarLocale();
  return (
    <div className="space-y-1">
      <Label>{label}</Label>
      <Popover>
        <PopoverTrigger asChild>
          <Button type="button" variant="outline" size="sm" className="w-full justify-start text-left font-normal">
            <CalendarIcon className="size-3.5" />
            {date
              ? format.dateTime(date, { day: "numeric", month: "short", year: "numeric" })
              : t("actionsCell.assignDialog.pickDate")}
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-auto p-0" align="start">
          <Calendar
            mode="single"
            selected={date}
            onSelect={(d) => onDateChange(d)}
            disabled={minDate ? { before: minDate } : undefined}
            locale={calendarLocale}
          />
        </PopoverContent>
      </Popover>
      <div className="flex gap-1">
        <Select value={hour} onValueChange={onHourChange}>
          <SelectTrigger className="w-16" aria-label={t("actionsCell.assignDialog.hourAria", { label })}>
            <SelectValue>{hour}</SelectValue>
          </SelectTrigger>
          <SelectContent className="max-h-48">
            {HOURS.map((h) => (
              <SelectItem key={h} value={h}>
                {h}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <span className="self-center text-muted-foreground text-xs">:</span>
        <Select value={minute} onValueChange={onMinuteChange}>
          <SelectTrigger className="w-16" aria-label={t("actionsCell.assignDialog.minuteAria", { label })}>
            <SelectValue>{minute}</SelectValue>
          </SelectTrigger>
          <SelectContent className="max-h-48">
            {MINUTES.map((m) => (
              <SelectItem key={m} value={m}>
                {m}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
    </div>
  );
}

function toIso(date: Date, hour: string, minute: string): string {
  const d = new Date(date);
  d.setHours(Number(hour), Number(minute), 0, 0);
  return d.toISOString();
}

function TransitionDialog({
  workOrderId,
  fromStatus,
  toStatus,
  open,
  onOpenChange,
}: {
  workOrderId: string;
  fromStatus: string;
  toStatus: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const t = useTranslations("workOrders");
  const tc = useTranslations("common");
  const queryClient = useQueryClient();
  const [reason, setReason] = useState("");
  const edge = TRANSITION_LABELS[`${fromStatus}->${toStatus}`] ?? toStatus;
  const label = t.has(`transition.${edge}`) ? t(`transition.${edge}`) : toStatus;

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
      toast.success(t("actionsCell.transitionDialog.moved", { status: label }));
      onOpenChange(false);
      setReason("");
    },
    onError: () => {
      toast.error(t("actionsCell.transitionDialog.failed"));
    },
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <WrenchIcon className="size-4" />
            {t("actionsCell.transitionDialog.title", { status: label, id: workOrderId })}
          </DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-1">
            <Label>{t("actionsCell.transitionDialog.reasonLabel")}</Label>
            <Textarea
              placeholder={t("actionsCell.transitionDialog.reasonPlaceholder")}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              rows={3}
            />
          </div>
          <div className="flex justify-end gap-2">
            <Button variant="ghost" size="sm" onClick={() => onOpenChange(false)}>
              {tc("cancel")}
            </Button>
            <Button size="sm" disabled={transitionMutation.isPending} onClick={() => transitionMutation.mutate()}>
              <WrenchIcon className="mr-1 size-3" />
              {transitionMutation.isPending
                ? t("actionsCell.transitionDialog.updating")
                : t("actionsCell.transitionDialog.move", { status: label })}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function ReportDialog({
  workOrderId,
  open,
  onOpenChange,
}: {
  workOrderId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const t = useTranslations("workOrders");
  const tc = useTranslations("common");
  const queryClient = useQueryClient();
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
      // The detail page's narrative reads from print-report — refresh it so the cockpit
      // shows the saved report without a manual reload ("everything happens here").
      await queryClient.invalidateQueries({ queryKey: ["/api/v1/workorders", workOrderId, "report"] });
      await queryClient.invalidateQueries({ queryKey: ["/api/v1/workorders", workOrderId, "print-report"] });
      setLoaded(false);
      toast.success(t("actionsCell.reportDialog.saved"));
      onOpenChange(false);
    } catch {
      toast.error(t("actionsCell.reportDialog.failed"));
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
            {t("actionsCell.reportDialog.title", { id: workOrderId })}
          </DialogTitle>
        </DialogHeader>
        {isLoading ? (
          <p className="text-muted-foreground text-sm">{t("actionsCell.reportDialog.loading")}</p>
        ) : (
          <div className="space-y-4">
            <div className="space-y-1">
              <Label>{t("actionsCell.reportDialog.chronological")}</Label>
              <Textarea
                placeholder={t("actionsCell.reportDialog.chronologicalPlaceholder")}
                value={chronological}
                onChange={(e) => setChronological(e.target.value)}
                rows={3}
              />
            </div>
            <div className="space-y-1">
              <Label>{t("actionsCell.reportDialog.analyze")}</Label>
              <Textarea
                placeholder={t("actionsCell.reportDialog.analyzePlaceholder")}
                value={analyze}
                onChange={(e) => setAnalyze(e.target.value)}
                rows={3}
              />
            </div>
            <div className="space-y-1">
              <Label>{t("actionsCell.reportDialog.corrective")}</Label>
              <Textarea
                placeholder={t("actionsCell.reportDialog.correctivePlaceholder")}
                value={corrective}
                onChange={(e) => setCorrective(e.target.value)}
                rows={3}
              />
            </div>
            <div className="space-y-1">
              <Label>{t("actionsCell.reportDialog.preventive")}</Label>
              <Textarea
                placeholder={t("actionsCell.reportDialog.preventivePlaceholder")}
                value={preventive}
                onChange={(e) => setPreventive(e.target.value)}
                rows={3}
              />
            </div>
            <div className="flex justify-end gap-2">
              <Button variant="ghost" size="sm" onClick={() => onOpenChange(false)}>
                {tc("cancel")}
              </Button>
              <Button size="sm" onClick={handleSave} disabled={saving}>
                {saving ? tc("saving") : t("actionsCell.reportDialog.save")}
              </Button>
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
