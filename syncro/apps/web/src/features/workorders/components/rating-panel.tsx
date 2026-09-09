"use client";

import { type ReactNode, useState } from "react";

import { useTranslations } from "next-intl";

import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { StarRating } from "@/features/workorders/components/star-rating";
import {
  useRateTechnician,
  useRateWorkorder,
  useRatingDimensions,
  useWorkorderRatings,
} from "@/features/workorders/hooks/use-ratings";
import type { RatingDimensionView, RatingView } from "@/features/workorders/types";
import { useListUsers } from "@/lib/api/generated/syncro";
import { useAuthUser } from "@/lib/auth/use-auth-user";

type Translator = ReturnType<typeof useTranslations>;

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
  const t = useTranslations("workOrders");
  const { data: dimensions, isLoading: loadingDimensions } = useRatingDimensions();
  const { data: existingRatings, isLoading: loadingRatings } = useWorkorderRatings(workorderId);
  const rateTechnician = useRateTechnician(workorderId);
  const rateWorkorder = useRateWorkorder(workorderId);
  const user = useAuthUser();
  const { data: usersRes, isLoading: isLoadingUsers } = useListUsers();

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
        <p className="text-muted-foreground text-sm">{t("ratings.panel.noDimensions")}</p>
      ) : (
        <>
          {technicianSection(
            t,
            dimensionList,
            canRateTechnician,
            submittedTechnician,
            executorPool,
            usersRes,
            isLoadingUsers,
            technicianId,
            setTechnicianId,
            technicianScores,
            setTechnicianScores,
            rateTechnician.isPending,
            () => rateTechnician.mutate({ ratedUserId: technicianId, scores: technicianScores }),
          )}
          {workorderSection(
            t,
            dimensionList,
            canRateWorkorder,
            submittedWorkorder,
            workorderScores,
            setWorkorderScores,
            rateWorkorder.isPending,
            () => rateWorkorder.mutate({ scores: workorderScores }),
          )}
          {!canRateTechnician && !canRateWorkorder && (
            <p className="text-muted-foreground text-sm">{t("ratings.panel.noPermission")}</p>
          )}
        </>
      )}
    </div>
  );
}

/** Read-only list of the submitted rating's per-dimension scores. */
function submittedScores(
  t: Translator,
  dimensionList: RatingDimensionView[],
  rating: RatingView,
  dimLabelKey: "submittedTechnicianDim" | "submittedWorkorderDim",
  heading: string,
) {
  return (
    <div className="space-y-2 rounded-lg border p-3">
      <h4 className="font-medium text-sm">{t("ratings.panel.submitted", { label: heading })}</h4>
      {dimensionList.map((dimension) => {
        const score = rating.scores.find((s) => s.dimensionCode === dimension.code)?.score ?? 0;
        return (
          <div key={dimension.id} className="flex items-center justify-between gap-2">
            <span className="text-sm">{dimension.label}</span>
            <StarRating value={score} readOnly label={t(`ratings.panel.${dimLabelKey}`, { label: dimension.label })} />
          </div>
        );
      })}
    </div>
  );
}

function technicianSection(
  t: Translator,
  dimensionList: RatingDimensionView[],
  canRate: boolean,
  submitted: RatingView | undefined,
  executorPool: string[],
  usersRes: { data?: Array<{ id?: string | null; loginIdentifier?: string | null }> } | undefined,
  isLoadingUsers: boolean,
  technicianId: string,
  setTechnicianId: (value: string) => void,
  scores: Record<string, number>,
  setScores: (updater: (prev: Record<string, number>) => Record<string, number>) => void,
  submitting: boolean,
  submit: () => void,
): ReactNode {
  if (submitted != null) {
    return submittedScores(t, dimensionList, submitted, "submittedTechnicianDim", t("ratings.panel.technicianRating"));
  }
  if (!canRate) {
    return null;
  }
  return (
    <div className="space-y-3 rounded-lg border p-3">
      <h4 className="font-medium text-sm">{t("ratings.panel.rateTechnician")}</h4>
      {executorPool.length > 0 ? (
        <>
          <Select value={technicianId} onValueChange={setTechnicianId}>
            <SelectTrigger aria-label={t("ratings.panel.technicianAria")} disabled={isLoadingUsers}>
              <SelectValue
                placeholder={isLoadingUsers ? t("ratings.panel.loading") : t("ratings.panel.selectTechnician")}
              />
            </SelectTrigger>
            <SelectContent>
              {executorPool.map((id) => (
                <SelectItem key={id} value={id}>
                  {usersRes?.data?.find((u) => u.id === id)?.loginIdentifier ?? id.slice(0, 8)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <div className="space-y-2">
            {dimensionList.map((dimension) => (
              <div key={dimension.id} className="flex items-center justify-between gap-2">
                <span className="text-sm">{dimension.label}</span>
                <StarRating
                  value={scores[dimension.code] ?? 0}
                  onChange={(value) => setScores((prev) => ({ ...prev, [dimension.code]: value }))}
                  label={t("ratings.panel.technicianDimLabel", { label: dimension.label })}
                />
              </div>
            ))}
          </div>
          <Button type="button" size="sm" disabled={submitting || Object.keys(scores).length === 0} onClick={submit}>
            {submitting ? t("ratings.panel.submitting") : t("ratings.panel.submitTechnician")}
          </Button>
        </>
      ) : (
        <p className="text-muted-foreground text-sm">{t("ratings.panel.noExecutingTechnicians")}</p>
      )}
    </div>
  );
}

function workorderSection(
  t: Translator,
  dimensionList: RatingDimensionView[],
  canRate: boolean,
  submitted: RatingView | undefined,
  scores: Record<string, number>,
  setScores: (updater: (prev: Record<string, number>) => Record<string, number>) => void,
  submitting: boolean,
  submit: () => void,
): ReactNode {
  if (submitted != null) {
    return submittedScores(t, dimensionList, submitted, "submittedWorkorderDim", t("ratings.panel.workorderRating"));
  }
  if (!canRate) {
    return null;
  }
  return (
    <div className="space-y-3 rounded-lg border p-3">
      <h4 className="font-medium text-sm">{t("ratings.panel.rateWorkorder")}</h4>
      <div className="space-y-2">
        {dimensionList.map((dimension) => (
          <div key={dimension.id} className="flex items-center justify-between gap-2">
            <span className="text-sm">{dimension.label}</span>
            <StarRating
              value={scores[dimension.code] ?? 0}
              onChange={(value) => setScores((prev) => ({ ...prev, [dimension.code]: value }))}
              label={t("ratings.panel.workorderDimLabel", { label: dimension.label })}
            />
          </div>
        ))}
      </div>
      <Button type="button" size="sm" disabled={submitting || Object.keys(scores).length === 0} onClick={submit}>
        {submitting ? t("ratings.panel.submitting") : t("ratings.panel.submitWorkorder")}
      </Button>
    </div>
  );
}
