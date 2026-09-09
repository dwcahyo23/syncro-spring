"use client";

import { useState } from "react";

import { useQueryClient } from "@tanstack/react-query";
import { Loader2Icon, Trash2 } from "lucide-react";
import { useTranslations } from "next-intl";
import { toast } from "sonner";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { usePlantScope } from "@/features/plant-scope/plant-scope-store";
import { errorResponse } from "@/lib/api/error-response";
import type { CreateMachineResponsibilityRequest } from "@/lib/api/generated/model";
import {
  getListMachineResponsibilitiesQueryKey,
  useAssignMachineResponsibility,
  useListMachineResponsibilities,
  useListMachines,
  useListUsers,
  useUnassignMachineResponsibility,
} from "@/lib/api/generated/syncro";
import { useAuthUser } from "@/lib/auth/use-auth-user";

const LEVEL_CODES = ["TECHNICIAN", "STAFF", "LEADER", "SPV", "MANAGER"] as const;

export function ResponsibilityManagement() {
  const t = useTranslations("masterData");
  const tc = useTranslations("common");
  const te = useTranslations("errors");
  const queryClient = useQueryClient();
  const user = useAuthUser();
  const plantScope = usePlantScope();
  const isViewer = user?.applicationRole === "AUDITOR";
  const plantId = plantScope.activePlantId === "all" ? undefined : plantScope.activePlantId;

  const { data: machinesRes, isLoading: isLoadingMachines } = useListMachines({
    plantId,
    page: 0,
    size: 100,
  });

  const { data: usersRes, isLoading: isLoadingUsers } = useListUsers();

  const { data: responsibilitiesRes, isLoading: isLoadingResponsibilities } = useListMachineResponsibilities({
    pageable: { page: 0, size: 100 },
  });

  const { mutate: assign, isPending: isAssigning } = useAssignMachineResponsibility({
    mutation: {
      onSuccess: () => {
        toast.success(t("responsibility.toast.assigned"));
        setMachineId("");
        setUserId("");
        setLevel("");
        queryClient.invalidateQueries({ queryKey: getListMachineResponsibilitiesQueryKey() });
      },
      onError: (error) => {
        const errorData = errorResponse(error);
        if (errorData?.code === "DUPLICATE_RESPONSIBILITY") {
          toast.error(te("DUPLICATE_RESPONSIBILITY"));
        } else {
          toast.error(t("responsibility.toast.assignFailed"));
        }
      },
    },
  });

  const { mutate: unassign, isPending: isUnassigning } = useUnassignMachineResponsibility({
    mutation: {
      onSuccess: () => {
        toast.success(t("responsibility.toast.unassigned"));
        setResponsibilityToDelete(null);
        queryClient.invalidateQueries({ queryKey: getListMachineResponsibilitiesQueryKey() });
      },
      onError: () => {
        toast.error(t("responsibility.toast.unassignFailed"));
      },
    },
  });

  const [machineId, setMachineId] = useState("");
  const [userId, setUserId] = useState("");
  const [level, setLevel] = useState("");

  const [responsibilityToDelete, setResponsibilityToDelete] = useState<string | null>(null);

  const handleAssign = (e: React.FormEvent) => {
    e.preventDefault();
    if (!machineId || !userId || !level) return;
    const request: CreateMachineResponsibilityRequest = {
      machineId,
      userId,
      level: level as CreateMachineResponsibilityRequest["level"],
    };
    assign({ data: request });
  };

  return (
    <div className="flex flex-col gap-6">
      <Card>
        <CardHeader>
          <CardTitle>{t("responsibility.title")}</CardTitle>
          <CardDescription>{t("responsibility.description")}</CardDescription>
        </CardHeader>
        <CardContent>
          {isViewer ? (
            <div className="p-4 bg-muted text-muted-foreground rounded-md text-sm">
              {t("responsibility.viewerNotice")}
            </div>
          ) : (
            <form onSubmit={handleAssign} className="grid grid-cols-1 md:grid-cols-4 gap-4 items-end">
              <div className="space-y-2">
                <Label htmlFor="machine">{t("responsibility.machine")}</Label>
                <Select value={machineId} onValueChange={setMachineId}>
                  <SelectTrigger aria-label={t("responsibility.machine")} disabled={isLoadingMachines}>
                    <SelectValue placeholder={isLoadingMachines ? tc("loading") : t("responsibility.selectMachine")} />
                  </SelectTrigger>
                  <SelectContent>
                    {machinesRes?.data?.items?.map((m) => (
                      <SelectItem key={m.id} value={m.id ?? ""}>
                        {m.code}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <div className="space-y-2">
                <Label htmlFor="user">{t("responsibility.user")}</Label>
                <Select value={userId} onValueChange={setUserId}>
                  <SelectTrigger aria-label={t("responsibility.user")} disabled={isLoadingUsers}>
                    <SelectValue placeholder={isLoadingUsers ? tc("loading") : t("responsibility.selectUser")} />
                  </SelectTrigger>
                  <SelectContent>
                    {usersRes?.data?.map((u) => (
                      <SelectItem key={u.id} value={u.id ?? ""}>
                        {u.displayName ?? u.loginIdentifier} ({u.nik ?? u.loginIdentifier})
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <div className="space-y-2">
                <Label htmlFor="level">{t("responsibility.level")}</Label>
                <Select value={level} onValueChange={setLevel}>
                  <SelectTrigger aria-label={t("responsibility.levelAria")}>
                    <SelectValue placeholder={t("responsibility.selectLevel")} />
                  </SelectTrigger>
                  <SelectContent>
                    {LEVEL_CODES.map((code) => (
                      <SelectItem key={code} value={code}>
                        {t(`responsibility.levelOptions.${code}`)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <Button type="submit" disabled={!machineId || !userId || !level || isAssigning}>
                {isAssigning ? <Loader2Icon className="mr-2 h-4 w-4 animate-spin" /> : null}
                {t("responsibility.assign")}
              </Button>
            </form>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>{t("responsibility.currentTitle")}</CardTitle>
          <CardDescription>{t("responsibility.currentDescription")}</CardDescription>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("responsibility.machine")}</TableHead>
                <TableHead>{t("responsibility.user")}</TableHead>
                <TableHead>{t("responsibility.level")}</TableHead>
                {!isViewer && <TableHead className="w-[100px] text-right">{tc("actions")}</TableHead>}
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoadingResponsibilities ? (
                <TableRow>
                  <TableCell colSpan={4} className="text-center text-muted-foreground h-24">
                    <Loader2Icon className="mx-auto h-6 w-6 animate-spin" />
                  </TableCell>
                </TableRow>
              ) : !responsibilitiesRes?.data?.items?.length ? (
                <TableRow>
                  <TableCell colSpan={4} className="text-center text-muted-foreground h-24">
                    {t("responsibility.emptyRow")}
                  </TableCell>
                </TableRow>
              ) : (
                responsibilitiesRes.data.items.map((r) => (
                  <TableRow key={r.id}>
                    <TableCell>
                      {machinesRes?.data?.items?.find((m) => m.id === r.machineId)?.code || r.machineId}
                    </TableCell>
                    <TableCell>{r.userName}</TableCell>
                    <TableCell>
                      {(LEVEL_CODES as readonly string[]).includes(String(r.level))
                        ? t(`responsibility.levelOptions.${r.level}`)
                        : r.level}
                    </TableCell>
                    {!isViewer && (
                      <TableCell className="text-right">
                        <Button
                          variant="ghost"
                          size="icon"
                          className="text-destructive hover:text-destructive hover:bg-destructive/10"
                          onClick={() => setResponsibilityToDelete(r.id ?? null)}
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </TableCell>
                    )}
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <AlertDialog open={!!responsibilityToDelete} onOpenChange={(open) => !open && setResponsibilityToDelete(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("responsibility.confirm.title")}</AlertDialogTitle>
            <AlertDialogDescription>{t("responsibility.confirm.description")}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{tc("cancel")}</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              onClick={() => responsibilityToDelete && unassign({ id: responsibilityToDelete })}
              disabled={isUnassigning}
            >
              {isUnassigning ? <Loader2Icon className="mr-2 h-4 w-4 animate-spin" /> : null}
              {t("responsibility.confirm.unassign")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
