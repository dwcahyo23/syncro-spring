"use client";

import { type FormEvent, useMemo, useState } from "react";

import { useQueryClient } from "@tanstack/react-query";
import { Loader2Icon, PowerOff, TriangleAlertIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useAssignSectionLeader, useClearSectionLeader } from "@/features/organization/hooks/use-section-leader";
import { usePlantScope } from "@/features/plant-scope/plant-scope-store";
import { apiErrorMessage, errorResponse } from "@/lib/api/error-response";
import type { CreateSectionRequest, SectionView } from "@/lib/api/generated/model";
import {
  getListSectionsQueryKey,
  useCreateSection,
  useListPlants,
  useListSections,
  useListUsers,
  useUpdateSection,
} from "@/lib/api/generated/syncro";
import { useAuthUser } from "@/lib/auth/use-auth-user";

const SECTION_CODES = ["MACHINERY", "UTILITY", "WORKSHOP"] as const;

type DialogMode = { type: "create" | "edit"; section?: SectionView };

interface SectionFormState {
  plantId: string;
  code: string;
  name: string;
}

const EMPTY_FORM: SectionFormState = { plantId: "", code: "", name: "" };

export function SectionManagement() {
  const t = useTranslations("masterData");
  const tc = useTranslations("common");
  const te = useTranslations("errors");
  const user = useAuthUser();
  const plantScope = usePlantScope();
  const scope = plantScope.scope;
  const activePlantId = plantScope.activePlantId;
  const queryClient = useQueryClient();
  const isAssignedEmpty = scope?.mode === "EMPTY";
  const [includeInactive, setIncludeInactive] = useState(true);

  const effectivePlantId = activePlantId !== "all" ? activePlantId : "";
  const sectionParams = {
    plantId: effectivePlantId || undefined,
    includeInactive,
  };
  const sectionsQuery = useListSections(sectionParams, {
    query: { enabled: Boolean(effectivePlantId) && !isAssignedEmpty },
  });
  const invalidateSections = () => queryClient.invalidateQueries({ queryKey: getListSectionsQueryKey() });
  const createSection = useCreateSection({ mutation: { onSuccess: invalidateSections } });
  const updateSection = useUpdateSection({ mutation: { onSuccess: invalidateSections } });
  const assignLeader = useAssignSectionLeader({ mutation: { onSuccess: invalidateSections } });
  const clearLeader = useClearSectionLeader({ mutation: { onSuccess: invalidateSections } });

  const plants = useListPlants({ query: { enabled: Boolean(scope) && !isAssignedEmpty } });
  const { data: usersRes } = useListUsers();
  const users = usersRes?.data ?? [];

  const [dialogMode, setDialogMode] = useState<DialogMode | null>(null);
  const [form, setForm] = useState<SectionFormState>(EMPTY_FORM);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [leaderFor, setLeaderFor] = useState<SectionView | null>(null);

  const canMutate = user?.applicationRole === "SUPER_ADMIN" || user?.applicationRole === "MANAGER_MAINTENANCE";
  const items = sectionsQuery.data?.data.items ?? [];
  const isSaving = createSection.isPending || updateSection.isPending;

  const scopeLabel = useMemo(() => {
    if (!scope) {
      return t("section.scope.loading");
    }
    if (scope.mode === "UNRESTRICTED") {
      return t("section.scope.allPlants");
    }
    if (scope.mode === "EMPTY") {
      return t("section.scope.noPlant");
    }
    return activePlantId === "all" ? t("section.scope.assignedPlants") : t("section.scope.activePlant");
  }, [activePlantId, scope, t]);

  function openCreateDialog() {
    setDialogMode({ type: "create" });
    setForm({ ...EMPTY_FORM, plantId: effectivePlantId });
    setFieldErrors({});
    setFormError(null);
  }

  function openEditDialog(section: SectionView) {
    setDialogMode({ type: "edit", section });
    setForm({ plantId: section.plantId ?? "", code: section.code ?? "", name: section.name ?? "" });
    setFieldErrors({});
    setFormError(null);
  }

  async function submitSection(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFieldErrors({});
    setFormError(null);

    try {
      if (dialogMode?.type === "edit" && dialogMode.section) {
        await updateSection.mutateAsync({
          sectionId: dialogMode.section.id ?? "",
          data: { name: form.name.trim(), active: dialogMode.section.active ?? true },
        });
        toast.success(t("section.toast.updated"));
      } else {
        const payload: CreateSectionRequest = {
          plantId: form.plantId,
          code: form.code as CreateSectionRequest["code"],
          name: form.name.trim(),
        };
        await createSection.mutateAsync({ data: payload });
        toast.success(t("section.toast.created"));
      }
      setDialogMode(null);
    } catch (error) {
      const response = errorResponse(error);
      setFieldErrors(response?.fieldErrors ?? {});
      const message = response ? apiErrorMessage(te, response) : t("section.toast.requestFailed");
      setFormError(message);
      toast.error(message);
    }
  }

  async function toggleActive(section: SectionView) {
    try {
      await updateSection.mutateAsync({
        sectionId: section.id ?? "",
        data: { name: section.name ?? "", active: !(section.active ?? true) },
      });
      toast.success(section.active ? t("section.toast.deactivated") : t("section.toast.reactivated"));
    } catch (error) {
      const response = errorResponse(error);
      if (response?.code === "SECTION_HAS_ACTIVE_MACHINE_GROUPS") {
        toast.error(te("SECTION_HAS_ACTIVE_MACHINE_GROUPS"));
      } else {
        toast.error(response ? apiErrorMessage(te, response) : t("section.toast.updateFailed"));
      }
    }
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>{t("section.title")}</CardTitle>
          <CardDescription>{t("section.description")}</CardDescription>
          <CardAction className="flex items-center gap-2">
            <Badge variant="outline">{scopeLabel}</Badge>
            {canMutate ? (
              <Button onClick={openCreateDialog}>{t("section.create")}</Button>
            ) : (
              <Badge variant="secondary">{t("section.readOnly")}</Badge>
            )}
          </CardAction>
        </CardHeader>
        <CardContent>
          {isAssignedEmpty ? (
            <SectionState
              title={t("section.state.noAssignmentTitle")}
              description={t("section.state.noAssignmentDesc")}
            />
          ) : null}
          {!effectivePlantId && !isAssignedEmpty ? (
            <SectionState
              title={t("section.state.selectPlantTitle")}
              description={t("section.state.selectPlantDesc")}
            />
          ) : null}
          {sectionsQuery.isLoading ? <SectionTableSkeleton /> : null}
          {sectionsQuery.isError ? (
            <SectionState
              title={t("section.state.loadFailedTitle")}
              description={t("section.state.loadFailedDesc")}
              action={
                <Button variant="outline" onClick={() => sectionsQuery.refetch()}>
                  {tc("retry")}
                </Button>
              }
            />
          ) : null}
          {effectivePlantId &&
          !sectionsQuery.isLoading &&
          !sectionsQuery.isError &&
          !isAssignedEmpty &&
          items.length === 0 ? (
            <SectionState title={t("section.state.emptyTitle")} description={t("section.state.emptyDesc")} />
          ) : null}
          {items.length > 0 ? (
            <>
              <div className="mb-2 flex items-center gap-2 text-muted-foreground text-xs">
                <Checkbox
                  id="show-inactive-sections"
                  checked={includeInactive}
                  onCheckedChange={(checked) => setIncludeInactive(checked === true)}
                />
                <label htmlFor="show-inactive-sections">{t("section.showInactive")}</label>
              </div>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{tc("code")}</TableHead>
                    <TableHead>{tc("name")}</TableHead>
                    <TableHead>{t("section.leader")}</TableHead>
                    <TableHead>{tc("status")}</TableHead>
                    <TableHead className="text-right">{tc("actions")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {items.map((section) => (
                    <TableRow key={section.id}>
                      <TableCell className="font-medium">{section.code}</TableCell>
                      <TableCell>{section.name}</TableCell>
                      <TableCell>
                        <LeaderCell users={users} leaderUserId={section.leaderUserId} />
                      </TableCell>
                      <TableCell>
                        {section.active ? (
                          <Badge variant="outline">{tc("active")}</Badge>
                        ) : (
                          <Badge variant="secondary">{tc("inactive")}</Badge>
                        )}
                      </TableCell>
                      <TableCell className="text-right">
                        {canMutate ? (
                          <div className="flex justify-end gap-2">
                            <Button
                              variant="outline"
                              size="sm"
                              onClick={() => setLeaderFor(section)}
                              disabled={!section.active}
                            >
                              {t("section.leader")}
                            </Button>
                            <Button variant="outline" size="sm" onClick={() => openEditDialog(section)}>
                              {tc("edit")}
                            </Button>
                            <Button
                              variant={section.active ? "destructive" : "outline"}
                              size="sm"
                              disabled={updateSection.isPending}
                              onClick={() => void toggleActive(section)}
                            >
                              <PowerOff />
                              {section.active ? tc("deactivate") : t("section.reactivate")}
                            </Button>
                          </div>
                        ) : (
                          <Badge variant="secondary">{t("section.viewOnly")}</Badge>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </>
          ) : null}
        </CardContent>
      </Card>

      <Dialog open={dialogMode !== null} onOpenChange={(open) => !open && setDialogMode(null)}>
        <DialogContent className="top-4 max-h-[calc(100svh-2rem)] translate-y-0 overflow-y-auto sm:max-w-2xl">
          <form onSubmit={submitSection} className="space-y-4">
            <DialogHeader>
              <DialogTitle>
                {dialogMode?.type === "edit" ? t("section.dialog.editTitle") : t("section.dialog.createTitle")}
              </DialogTitle>
              <DialogDescription>{t("section.dialog.description")}</DialogDescription>
            </DialogHeader>
            {formError ? (
              <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">{formError}</p>
            ) : null}
            {dialogMode?.type === "create" ? (
              <>
                <div className="grid gap-2">
                  <Label htmlFor="section-plant">{tc("plant")}</Label>
                  <Select value={form.plantId} onValueChange={(value) => setForm((c) => ({ ...c, plantId: value }))}>
                    <SelectTrigger id="section-plant" aria-invalid={Boolean(fieldErrors.plantId)} disabled={isSaving}>
                      <SelectValue placeholder={t("section.dialog.selectPlant")} />
                    </SelectTrigger>
                    <SelectContent>
                      {(plants.data?.data.items ?? []).map((plant) => (
                        <SelectItem key={plant.id} value={plant.id ?? ""}>
                          {plant.code} — {plant.name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  {fieldErrors.plantId ? <p className="text-destructive text-sm">{fieldErrors.plantId}</p> : null}
                </div>
                <div className="grid gap-2">
                  <Label htmlFor="section-code">{tc("code")}</Label>
                  <Select value={form.code} onValueChange={(value) => setForm((c) => ({ ...c, code: value }))}>
                    <SelectTrigger id="section-code" aria-invalid={Boolean(fieldErrors.code)} disabled={isSaving}>
                      <SelectValue placeholder={t("section.dialog.selectCode")} />
                    </SelectTrigger>
                    <SelectContent>
                      {SECTION_CODES.map((code) => (
                        <SelectItem key={code} value={code}>
                          {code}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  {fieldErrors.code ? <p className="text-destructive text-sm">{fieldErrors.code}</p> : null}
                </div>
              </>
            ) : null}
            <div className="grid gap-2">
              <Label htmlFor="section-name">{tc("name")}</Label>
              <Input
                id="section-name"
                value={form.name}
                onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
                aria-invalid={Boolean(fieldErrors.name)}
                disabled={isSaving}
              />
              {fieldErrors.name ? <p className="text-destructive text-sm">{fieldErrors.name}</p> : null}
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setDialogMode(null)} disabled={isSaving}>
                {tc("cancel")}
              </Button>
              <Button type="submit" disabled={isSaving}>
                {isSaving ? <Loader2Icon className="animate-spin" /> : null}
                {t("section.dialog.save")}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Section leader picker */}
      <Dialog open={leaderFor !== null} onOpenChange={(open) => !open && setLeaderFor(null)}>
        <DialogContent>
          <div className="space-y-4">
            <DialogHeader>
              <DialogTitle>{t("section.leaderDialog.title", { name: leaderFor?.name ?? "" })}</DialogTitle>
              <DialogDescription>{t("section.leaderDialog.description")}</DialogDescription>
            </DialogHeader>
            <div className="grid gap-2">
              <Label htmlFor="section-leader-user">{t("section.leader")}</Label>
              <Select
                value={leaderFor?.leaderUserId ?? ""}
                onValueChange={(value) => {
                  if (!leaderFor?.id || value === "") {
                    return;
                  }
                  void assignLeader.mutateAsync({ sectionId: leaderFor.id, userId: value }).then(() => {
                    toast.success(t("section.toast.leaderAssigned"));
                    setLeaderFor(null);
                  });
                }}
              >
                <SelectTrigger id="section-leader-user">
                  <SelectValue placeholder={t("section.leaderDialog.selectPlaceholder")} />
                </SelectTrigger>
                <SelectContent>
                  {users.map((u) => (
                    <SelectItem key={u.id} value={u.id ?? ""}>
                      {u.displayName ?? u.loginIdentifier} ({u.nik ?? u.loginIdentifier})
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            {leaderFor?.leaderUserId ? (
              <Button
                variant="outline"
                className="w-full"
                disabled={clearLeader.isPending}
                onClick={() => {
                  if (!leaderFor?.id) {
                    return;
                  }
                  void clearLeader.mutateAsync(leaderFor.id).then(() => {
                    toast.success(t("section.toast.leaderCleared"));
                    setLeaderFor(null);
                  });
                }}
              >
                {clearLeader.isPending ? <Loader2Icon className="animate-spin" /> : null}
                {t("section.leaderDialog.clear")}
              </Button>
            ) : null}
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setLeaderFor(null)}>
                {tc("close")}
              </Button>
            </DialogFooter>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function LeaderCell({
  users,
  leaderUserId,
}: {
  users: Array<{ id?: string; loginIdentifier?: string; displayName?: string | null; nik?: string | null }>;
  leaderUserId: string | null | undefined;
}) {
  const t = useTranslations("masterData");
  if (!leaderUserId) {
    return <span className="text-muted-foreground text-xs italic">{t("section.unassigned")}</span>;
  }
  const found = users.find((u) => u.id === leaderUserId);
  return <>{found ? (found.displayName ?? found.loginIdentifier ?? leaderUserId) : leaderUserId}</>;
}

function SectionState({
  title,
  description,
  action,
}: {
  title: string;
  description: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 rounded-lg border border-dashed p-8 text-center">
      <TriangleAlertIcon className="size-8 text-muted-foreground" />
      <div>
        <h2 className="font-medium">{title}</h2>
        <p className="text-muted-foreground text-sm">{description}</p>
      </div>
      {action}
    </div>
  );
}

function SectionTableSkeleton() {
  return (
    <div className="space-y-2">
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-10 w-full" />
    </div>
  );
}
