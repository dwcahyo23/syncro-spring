"use client";

import { useState } from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export type EscalationStepStatus = "sent" | "queued" | "failed" | "pending" | "stopped" | "rate-limited";

export interface EscalationStep {
  level: string; // TECHNICIAN | STAFF | LEADER | SPV | MANAGER
  recipientDisplayName: string | null;
  recipientPhoneMasked: string | null;
  status: EscalationStepStatus;
  rawStatus: string; // original NotificationJobStatus for debugging
  timestamp?: string; // ISO from sentAt/createdAt/updatedAt
  nextSendAt?: string; // from nextAttemptAt where applicable
  deliveryResult?: string; // errorDetail first 120 chars
  traceId?: string;
}

export interface EscalationTimelineProps {
  steps: EscalationStep[];
  alertStatus: "OPEN" | "ACKNOWLEDGED" | "RESOLVED";
  expandedByDefault?: boolean;
}

const STATUS_VARIANT_MAP: Record<EscalationStepStatus, { label: string; className: string; dotClass: string }> = {
  sent: {
    label: "Sent",
    className:
      "border-transparent bg-[var(--syncro-status-healthy-bg)] text-[var(--syncro-status-healthy)] border-[var(--syncro-status-healthy-border)]",
    dotClass: "bg-[var(--syncro-status-healthy)]",
  },
  queued: {
    label: "Queued",
    className:
      "border-transparent bg-[var(--syncro-status-info-bg)] text-[var(--syncro-status-info)] border-[var(--syncro-status-info-border)]",
    dotClass: "bg-[var(--syncro-status-info)]",
  },
  pending: {
    label: "Pending",
    className:
      "border-transparent bg-[var(--syncro-status-info-bg)] text-[var(--syncro-status-info)] border-[var(--syncro-status-info-border)]",
    dotClass: "bg-[var(--syncro-status-info)]",
  },
  failed: {
    label: "Failed",
    className:
      "border-transparent bg-[var(--syncro-status-critical-bg)] text-[var(--syncro-status-critical)] border-[var(--syncro-status-critical-border)]",
    dotClass: "bg-[var(--syncro-status-critical)]",
  },
  stopped: {
    label: "Stopped",
    className:
      "border-transparent bg-[var(--syncro-status-neutral-bg)] text-[var(--syncro-status-neutral)] border-[var(--syncro-status-neutral-border)]",
    dotClass: "bg-[var(--syncro-status-neutral)]",
  },
  "rate-limited": {
    label: "Rate limited",
    className:
      "border-transparent bg-[var(--syncro-status-warning-bg)] text-[var(--syncro-status-warning)] border-[var(--syncro-status-warning-border)]",
    dotClass: "bg-[var(--syncro-status-warning)]",
  },
};

function formatTimestamp(iso?: string) {
  if (!iso) return null;
  try {
    return new Intl.DateTimeFormat("en", {
      dateStyle: "medium",
      timeStyle: "short",
    }).format(new Date(iso));
  } catch {
    return iso;
  }
}

function StepBadge({ status, rawStatus }: { status: EscalationStepStatus; rawStatus: string }) {
  const cfg = STATUS_VARIANT_MAP[status] ?? STATUS_VARIANT_MAP.pending;
  const label = cfg.label;
  const showRaw =
    rawStatus &&
    rawStatus !== label.toUpperCase() &&
    !["PENDING", "SENT", "ESCALATED", "ROUTING_FAILED", "EXHAUSTED", "CANCELLED"].includes(rawStatus);
  return (
    <span className="inline-flex items-center gap-1.5">
      <Badge variant="outline" className={cn("border", cfg.className)}>
        {label}
      </Badge>
      {showRaw ? <span className="font-mono-tight text-muted-foreground text-xs">({rawStatus})</span> : null}
    </span>
  );
}

function renderRecipient(step: EscalationStep) {
  if (step.recipientDisplayName) {
    return (
      <span className="text-muted-foreground text-sm" title={step.recipientPhoneMasked ?? undefined}>
        {step.recipientDisplayName}
        {step.recipientPhoneMasked ? ` · ${step.recipientPhoneMasked}` : ""}
      </span>
    );
  }
  if (step.recipientPhoneMasked) {
    return <span className="text-muted-foreground text-sm">{step.recipientPhoneMasked}</span>;
  }
  return <span className="text-muted-foreground text-sm">—</span>;
}

export function EscalationTimeline({
  steps,
  alertStatus: _alertStatus,
  expandedByDefault = true,
}: EscalationTimelineProps) {
  const [showAll, setShowAll] = useState(expandedByDefault);

  if (!steps || steps.length === 0) {
    return null;
  }

  // Mobile: show latest first if collapsed
  const displaySteps = showAll ? steps : [...steps].slice().reverse().slice(0, 1);

  return (
    <div className="space-y-3">
      {/* Desktop timeline — always expanded on desktop per spec */}
      <div className="relative hidden md:block">
        <div className="relative">
          <div className="absolute left-[7px] top-2 bottom-2 w-px bg-border" aria-hidden="true" />
          <ol className="space-y-4" aria-label="Escalation timeline">
            {steps.map((step, idx) => {
              const cfg = STATUS_VARIANT_MAP[step.status] ?? STATUS_VARIANT_MAP.pending;
              const ts = formatTimestamp(step.timestamp);
              const nextAt = formatTimestamp(step.nextSendAt);
              return (
                <li
                  key={step.level}
                  className="relative flex gap-3 pl-6"
                  aria-label={`${step.level} ${step.recipientDisplayName ?? ""} ${step.status} ${ts ?? ""}`}
                >
                    <span
                      className={cn(
                        "absolute left-0 top-1.5 h-3.5 w-3.5 rounded-full border-2 border-background shadow-sm",
                        cfg.dotClass,
                      )}
                      aria-hidden="true"
                    />
                    <div className="flex-1 min-w-0 space-y-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="text-sm font-semibold">{step.level}</span>
                        <StepBadge status={step.status} rawStatus={step.rawStatus} />
                        {renderRecipient(step)}
                      </div>
                      <div className="flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted-foreground">
                        {ts ? (
                          <time dateTime={step.timestamp} className="font-mono-tight">
                            {ts}
                          </time>
                        ) : null}
                        {nextAt ? <span>Next: {nextAt}</span> : null}
                        {step.traceId ? (
                          <span className="font-mono-tight" title={step.traceId}>
                            trace:{step.traceId.slice(0, 8)}…
                          </span>
                        ) : null}
                      </div>
                      {step.deliveryResult ? (
                        <p className="text-xs text-muted-foreground break-words" title={step.deliveryResult}>
                          {step.deliveryResult.length > 120
                            ? `${step.deliveryResult.slice(0, 120)}…`
                            : step.deliveryResult}
                        </p>
                      ) : null}
                      {/* ESCALATED handoff marker */}
                      {step.rawStatus === "ESCALATED" ? (
                        <p className="text-xs text-[var(--syncro-status-healthy)]">
                          Escalated to {steps[idx + 1]?.level ?? "next level"} at {ts ?? ""}
                        </p>
                      ) : null}
                      {step.rawStatus === "CANCELLED" ? (
                        <p className="text-xs text-muted-foreground">Stopped by acknowledgement at {ts ?? ""}</p>
                      ) : null}
                      {step.rawStatus === "ROUTING_FAILED" && step.deliveryResult ? null : null}
                    </div>
                  </li>
                );
              })}
            </ol>
        </div>
      </div>

      {/* Mobile stacked cards */}
      <div className="md:hidden">
        {!showAll ? (
          <div className="space-y-2">
            {displaySteps.map((step) => {
              const ts = formatTimestamp(step.timestamp);
              return (
                <div key={step.level} className="rounded-lg border p-3 space-y-1.5">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-sm font-semibold">{step.level}</span>
                    <StepBadge status={step.status} rawStatus={step.rawStatus} />
                  </div>
                  <p className="text-sm">
                    {step.recipientDisplayName ? `${step.recipientDisplayName}` : "—"}
                    {step.recipientPhoneMasked ? ` · ${step.recipientPhoneMasked}` : ""}
                  </p>
                  {ts ? <p className="font-mono-tight text-xs text-muted-foreground">{ts}</p> : null}
                  {step.deliveryResult ? (
                    <p className="text-xs text-muted-foreground break-words">{step.deliveryResult.slice(0, 120)}</p>
                  ) : null}
                </div>
              );
            })}
            {steps.length > 1 ? (
              <Button variant="outline" size="sm" className="w-full" onClick={() => setShowAll(true)}>
                Show full timeline ({steps.length} levels)
              </Button>
            ) : null}
          </div>
        ) : (
          <div className="space-y-2">
            {steps.map((step) => {
              const ts = formatTimestamp(step.timestamp);
              return (
                <div key={step.level} className="rounded-lg border p-3 space-y-1.5">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-sm font-semibold">{step.level}</span>
                    <StepBadge status={step.status} rawStatus={step.rawStatus} />
                  </div>
                  <p className="text-sm">
                    {step.recipientDisplayName ? `${step.recipientDisplayName}` : "—"}
                    {step.recipientPhoneMasked ? ` · ${step.recipientPhoneMasked}` : ""}
                  </p>
                  {ts ? <p className="font-mono-tight text-xs text-muted-foreground">{ts}</p> : null}
                  {step.deliveryResult ? (
                    <p className="break-words text-xs text-muted-foreground">{step.deliveryResult.slice(0, 120)}</p>
                  ) : null}
                </div>
              );
            })}
            {steps.length > 1 ? (
              <Button variant="ghost" size="sm" className="w-full" onClick={() => setShowAll(false)}>
                Show less
              </Button>
            ) : null}
          </div>
        )}
      </div>

      {/* Desktop toggle — only show when collapsed initially */}
      {!expandedByDefault && showAll && steps.length > 1 ? (
        <div className="hidden md:block">
          <Button variant="ghost" size="sm" onClick={() => setShowAll(false)}>
            Show latest only
          </Button>
        </div>
      ) : null}
    </div>
  );
}
