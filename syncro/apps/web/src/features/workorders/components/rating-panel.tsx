"use client";

import { type ReactNode, useState } from "react";

import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { StarRating } from "@/features/workorders/components/star-rating";
import {
  useRateTechnician,
  useRateWorkorder,
  useRatingDimensions,
  useWorkorderRatings,
} from "@/features/workorders/hooks/use-ratings";
import type { RatingDimensionView, RatingView } from "@/features/workorders/types";
import { useAuthUser } from "@/lib/auth/use-auth-user";

export interface RatingPanelProps {
  workorderId: string;
  /** Executor pool (assigned technician + session technicians) for the technician rating. */
  executorPool: string[];
}

/**
 * Star-rating panel for one closed workorder (story 10-8). Lists the shared rating
 * dimensions and lets the user submit a technician and/or workorder rating depending
 * on their role. Already-submitted ratings render read-only (immutable, FR-121/FR-124).
 */
export function RatingPanel({ workorderId, executorPool }: RatingPanelProps) {
  const { data: dimensions, isLoading: loadingDimensions } = useRatingDimensions();
  const { data: existingRatings, isLoading: loadingRatings } = useWorkorderRatings(workorderId);
  const rateTechnician = useRateTechnician(workorderId);
  const rateWorkorder = useRateWorkorder(workorderId);
  const user = useAuthUser();

  // UI-only gate (security is server-side): SECTION_LEADER rates technicians, SUPER_ADMIN
  // is exempt; PRODUCTION_LEADER rates workorders. Machine-group/plant scope is enforced
  // by the backend at submit time.
  const role = user?.applicationRole;
  const canRateTechnician = role === "SECTION_LEADER" || role === "SUPER_ADMIN";
  const canRateWorkorder = role === "PRODUCTION_LEADER" || role === "SUPER_ADMIN";

  const [technicianId, setTechnicianId] = useState<string>(executorPool[0] ?? "");
  const [technicianScores, setTechnicianScores] = useState<Record<string, number>>({});
  const [workorderScores, setWorkorderScores] = useState<Record<string, number>>({});

  if (loadingDimensions || loadingRatings) {
    return (
      <div className="space-y-2">
        <Skeleton className="h-4 w-40" />
        <Skeleton className="h-8 w-full" />
      </div>
    );
  }

  const dimensionList: RatingDimensionView[] = dimensions ?? [];
  const submittedTechnician = existingRatings?.find((r) => r.ratingType === "TECHNICIAN");
  const submittedWorkorder = existingRatings?.find((r) => r.ratingType === "WORKORDER");

  return (
    <div className="space-y-4">
      {dimensionList.length === 0 ? (
        <p className="text-muted-foreground text-sm">No rating dimensions configured yet.</p>
      ) : (
        <>
          {technicianSection(
            dimensionList,
            canRateTechnician,
            submittedTechnician,
            executorPool,
            technicianId,
            setTechnicianId,
            technicianScores,
            setTechnicianScores,
            rateTechnician.isPending,
            () => rateTechnician.mutate({ ratedUserId: technicianId, scores: technicianScores }),
          )}
          {workorderSection(
            dimensionList,
            canRateWorkorder,
            submittedWorkorder,
            workorderScores,
            setWorkorderScores,
            rateWorkorder.isPending,
            () => rateWorkorder.mutate({ scores: workorderScores }),
          )}
          {!canRateTechnician && !canRateWorkorder && (
            <p className="text-muted-foreground text-sm">You do not have permission to rate this workorder.</p>
          )}
        </>
      )}
    </div>
  );
}

/** Read-only list of the submitted rating's per-dimension scores. */
function submittedScores(dimensionList: RatingDimensionView[], rating: RatingView, label: string) {
  return (
    <div className="space-y-2 rounded-lg border p-3">
      <h4 className="font-medium text-sm">{label} (submitted)</h4>
      {dimensionList.map((dimension) => {
        const score = rating.scores.find((s) => s.dimensionCode === dimension.code)?.score ?? 0;
        return (
          <div key={dimension.id} className="flex items-center justify-between gap-2">
            <span className="text-sm">{dimension.label}</span>
            <StarRating value={score} readOnly label={`${label} ${dimension.label}`} />
          </div>
        );
      })}
    </div>
  );
}

function technicianSection(
  dimensionList: RatingDimensionView[],
  canRate: boolean,
  submitted: RatingView | undefined,
  executorPool: string[],
  technicianId: string,
  setTechnicianId: (value: string) => void,
  scores: Record<string, number>,
  setScores: (updater: (prev: Record<string, number>) => Record<string, number>) => void,
  submitting: boolean,
  submit: () => void,
): ReactNode {
  if (submitted != null) {
    return submittedScores(dimensionList, submitted, "Technician rating");
  }
  if (!canRate) {
    return null;
  }
  return (
    <div className="space-y-3 rounded-lg border p-3">
      <h4 className="font-medium text-sm">Rate a technician</h4>
      {executorPool.length > 0 ? (
        <>
          <select
            value={technicianId}
            onChange={(event) => setTechnicianId(event.target.value)}
            aria-label="Technician to rate"
            className="h-9 w-full rounded-md border bg-transparent px-3 py-1 text-sm"
          >
            {executorPool.map((id) => (
              <option key={id} value={id}>
                {id}
              </option>
            ))}
          </select>
          <div className="space-y-2">
            {dimensionList.map((dimension) => (
              <div key={dimension.id} className="flex items-center justify-between gap-2">
                <span className="text-sm">{dimension.label}</span>
                <StarRating
                  value={scores[dimension.code] ?? 0}
                  onChange={(value) => setScores((prev) => ({ ...prev, [dimension.code]: value }))}
                  label={`Technician ${dimension.label}`}
                />
              </div>
            ))}
          </div>
          <Button type="button" size="sm" disabled={submitting || Object.keys(scores).length === 0} onClick={submit}>
            {submitting ? "Submitting…" : "Submit technician rating"}
          </Button>
        </>
      ) : (
        <p className="text-muted-foreground text-sm">No executing technicians on this workorder.</p>
      )}
    </div>
  );
}

function workorderSection(
  dimensionList: RatingDimensionView[],
  canRate: boolean,
  submitted: RatingView | undefined,
  scores: Record<string, number>,
  setScores: (updater: (prev: Record<string, number>) => Record<string, number>) => void,
  submitting: boolean,
  submit: () => void,
): ReactNode {
  if (submitted != null) {
    return submittedScores(dimensionList, submitted, "Workorder rating");
  }
  if (!canRate) {
    return null;
  }
  return (
    <div className="space-y-3 rounded-lg border p-3">
      <h4 className="font-medium text-sm">Rate the workorder</h4>
      <div className="space-y-2">
        {dimensionList.map((dimension) => (
          <div key={dimension.id} className="flex items-center justify-between gap-2">
            <span className="text-sm">{dimension.label}</span>
            <StarRating
              value={scores[dimension.code] ?? 0}
              onChange={(value) => setScores((prev) => ({ ...prev, [dimension.code]: value }))}
              label={`Workorder ${dimension.label}`}
            />
          </div>
        ))}
      </div>
      <Button type="button" size="sm" disabled={submitting || Object.keys(scores).length === 0} onClick={submit}>
        {submitting ? "Submitting…" : "Submit workorder rating"}
      </Button>
    </div>
  );
}
