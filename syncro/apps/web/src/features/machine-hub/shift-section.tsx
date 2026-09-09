"use client";

import { useEffect, useRef, useState } from "react";

import { useQueryClient } from "@tanstack/react-query";
import { Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { toast } from "sonner";

import { InheritedConfigBadge } from "@/components/syncro/inherited-config-badge";
import { ShiftConfigEditor, type ShiftWindowInput } from "@/components/syncro/shift-config-editor";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { type ApiErrorResponse, errorResponse } from "@/lib/api/error-response";
import {
  getGetMachineShiftConfigQueryKey,
  useDeleteMachineShiftConfig,
  useGetMachineShiftConfig,
  useUpdateMachineShiftConfig,
} from "@/lib/api/generated/syncro";
import { useAuthUser } from "@/lib/auth/use-auth-user";

/**
 * Resolved shift schedule for one machine (Story 8-5). The backend owns
 * resolution precedence (MACHINE > MACHINE_GROUP > NONE); this section only
 * renders the resolved source and lets LEADER+ users maintain the override.
 */
export function ShiftSection({ machineId }: { machineId: string }) {
  const t = useTranslations("machineHub.shift");
  const user = useAuthUser();
  const canMutate = user?.applicationRole === "SUPER_ADMIN" || user?.applicationRole === "MANAGER_MAINTENANCE";
  const queryClient = useQueryClient();
  const shiftConfig = useGetMachineShiftConfig(machineId);
  const updateShifts = useUpdateMachineShiftConfig();
  const deleteShifts = useDeleteMachineShiftConfig();
  const source = shiftConfig.data?.data.source;
  const [shifts, setShifts] = useState<ShiftWindowInput[]>([]);
  const [error, setError] = useState<string | null>(null);
  const shiftsDirtyRef = useRef(false);

  useEffect(() => {
    // A background refetch (window focus, invalidation) must never clobber in-progress edits.
    if (shiftsDirtyRef.current) {
      return;
    }
    const storedShifts = shiftConfig.data?.data.shifts ?? [];
    setShifts(storedShifts.map((window) => ({ startTime: window.startTime ?? "", endTime: window.endTime ?? "" })));
  }, [shiftConfig.data]);

  function updateShiftRows(next: ShiftWindowInput[]) {
    shiftsDirtyRef.current = true;
    setShifts(next);
  }

  function hasIncompleteRow() {
    return shifts.some((window) => window.startTime === "" || window.endTime === "");
  }

  function shiftErrorMessage(response: ApiErrorResponse | null): string {
    return response?.fieldErrors?.shifts ?? response?.message ?? t("requestFailed");
  }

  async function saveOverride() {
    setError(null);
    if (hasIncompleteRow()) {
      setError(t("incompleteRows"));
      return;
    }

    try {
      await updateShifts.mutateAsync({ machineId, data: { shifts } });
      shiftsDirtyRef.current = false;
      queryClient.invalidateQueries({ queryKey: getGetMachineShiftConfigQueryKey(machineId) });
      toast.success(t("savedToast"));
    } catch (caught) {
      const response = errorResponse(caught);
      setError(shiftErrorMessage(response));
      toast.error(shiftErrorMessage(response));
    }
  }

  async function clearOverride() {
    setError(null);

    try {
      await deleteShifts.mutateAsync({ machineId });
      shiftsDirtyRef.current = false;
      queryClient.invalidateQueries({ queryKey: getGetMachineShiftConfigQueryKey(machineId) });
      toast.success(t("clearedToast"));
    } catch (caught) {
      const response = errorResponse(caught);
      setError(shiftErrorMessage(response));
      toast.error(shiftErrorMessage(response));
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          {t("title")}
          <InheritedConfigBadge source={source} />
        </CardTitle>
        <CardDescription>{t("description")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {shiftConfig.status === "pending" ? (
          <div className="space-y-2">
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-full" />
          </div>
        ) : null}
        {shiftConfig.status === "error" ? <p className="text-muted-foreground text-sm">{t("loadFailed")}</p> : null}
        {shiftConfig.status === "success" ? (
          <>
            {source === "NONE" ? <p className="text-muted-foreground text-sm">{t("noneConfigured")}</p> : null}
            <ShiftConfigEditor
              value={shifts}
              onChange={updateShiftRows}
              error={error ?? undefined}
              readOnly={!canMutate || updateShifts.isPending || deleteShifts.isPending}
            />
            {canMutate ? (
              <div className="flex gap-2">
                <Button onClick={() => void saveOverride()} disabled={updateShifts.isPending || deleteShifts.isPending}>
                  {updateShifts.isPending ? <Loader2Icon className="animate-spin" /> : null}
                  {t("save")}
                </Button>
                {source === "MACHINE" ? (
                  <Button
                    variant="outline"
                    onClick={() => void clearOverride()}
                    disabled={updateShifts.isPending || deleteShifts.isPending}
                  >
                    {t("clear")}
                  </Button>
                ) : null}
              </div>
            ) : null}
          </>
        ) : null}
      </CardContent>
    </Card>
  );
}
