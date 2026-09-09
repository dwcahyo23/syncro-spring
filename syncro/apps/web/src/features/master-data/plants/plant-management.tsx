"use client";

import { type FormEvent, useMemo, useState } from "react";

import { useQueryClient } from "@tanstack/react-query";
import { Loader2Icon, Trash2, TriangleAlertIcon } from "lucide-react";
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
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
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
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { usePlantScope } from "@/features/plant-scope/plant-scope-store";
import { apiErrorMessage, errorResponse } from "@/lib/api/error-response";
import type { PlantRequest, PlantView } from "@/lib/api/generated/model";
import {
  getPlantScopeQueryKey,
  useCreatePlant,
  useDeletePlant,
  useListPlants,
  useUpdatePlant,
} from "@/lib/api/generated/syncro";
import { useAuthUser } from "@/lib/auth/use-auth-user";
import { useDateTimeFormatter } from "@/lib/i18n/format";

type PlantFormState = PlantRequest;

type DialogMode = { type: "create"; plant?: never } | { type: "edit"; plant: PlantView };

const EMPTY_FORM: PlantFormState = { code: "", name: "" };

export function PlantManagement() {
  const t = useTranslations("masterData");
  const tc = useTranslations("common");
  const te = useTranslations("errors");
  const dt = useDateTimeFormatter();
  const user = useAuthUser();
  const plantScope = usePlantScope();
  const scope = plantScope.scope;
  const activePlantId = plantScope.activePlantId;
  const queryClient = useQueryClient();
  const isAssignedEmpty = scope?.mode === "EMPTY";
  const plantsQueryKey = ["plants", activePlantId] as const;
  const plants = useListPlants({ query: { enabled: Boolean(scope) && !isAssignedEmpty, queryKey: plantsQueryKey } });
  const invalidatePlants = () => queryClient.invalidateQueries({ queryKey: plantsQueryKey });
  const invalidatePlantScope = () => queryClient.invalidateQueries({ queryKey: getPlantScopeQueryKey() });
  const invalidatePlantData = () => {
    invalidatePlants();
    invalidatePlantScope();
  };
  const createPlant = useCreatePlant({ mutation: { onSuccess: invalidatePlantData } });
  const updatePlant = useUpdatePlant({ mutation: { onSuccess: invalidatePlants } });
  const deletePlant = useDeletePlant({ mutation: { onSuccess: invalidatePlantData } });
  const [dialogMode, setDialogMode] = useState<DialogMode | null>(null);
  const [form, setForm] = useState<PlantFormState>(EMPTY_FORM);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<PlantView | null>(null);

  const canMutate = user?.applicationRole === "SUPER_ADMIN" || user?.applicationRole === "MANAGER_MAINTENANCE";
  const plantItems = plants.data?.data.items ?? [];
  const isSaving = createPlant.isPending || updatePlant.isPending;

  const scopeLabel = useMemo(() => {
    if (!scope) {
      return t("plant.scope.loading");
    }
    if (scope.mode === "UNRESTRICTED") {
      return t("plant.scope.allPlants");
    }
    if (scope.mode === "EMPTY") {
      return t("plant.scope.noPlant");
    }
    return activePlantId === "all" ? t("plant.scope.assignedPlants") : t("plant.scope.activePlant");
  }, [activePlantId, scope, t]);

  function openCreateDialog() {
    setDialogMode({ type: "create" });
    setForm(EMPTY_FORM);
    setFieldErrors({});
    setFormError(null);
  }

  function openEditDialog(plant: PlantView) {
    setDialogMode({ type: "edit", plant });
    setForm({ code: plant.code ?? "", name: plant.name ?? "" });
    setFieldErrors({});
    setFormError(null);
  }

  async function submitPlant(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFieldErrors({});
    setFormError(null);

    try {
      if (dialogMode?.type === "edit") {
        await updatePlant.mutateAsync({ plantId: dialogMode.plant.id ?? "", data: form });
        toast.success(t("plant.toast.updated"));
      } else {
        await createPlant.mutateAsync({ data: form });
        toast.success(t("plant.toast.created"));
      }
      setDialogMode(null);
    } catch (error) {
      const response = errorResponse(error);
      setFieldErrors(response?.fieldErrors ?? {});
      const message = response ? apiErrorMessage(te, response) : t("plant.toast.requestFailed");
      setFormError(message);
      toast.error(message);
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) {
      return;
    }

    try {
      await deletePlant.mutateAsync({ plantId: deleteTarget.id ?? "" });
      toast.success(t("plant.toast.deleted"));
      setDeleteTarget(null);
    } catch (error) {
      const response = errorResponse(error);
      toast.error(response ? apiErrorMessage(te, response) : t("plant.toast.deleteFailed"));
    }
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>{t("plant.title")}</CardTitle>
          <CardDescription>{t("plant.description")}</CardDescription>
          <CardAction className="flex items-center gap-2">
            <Badge variant="outline">{scopeLabel}</Badge>
            {canMutate ? (
              <Button onClick={openCreateDialog}>{t("plant.create")}</Button>
            ) : (
              <Badge variant="secondary">{t("plant.readOnly")}</Badge>
            )}
          </CardAction>
        </CardHeader>
        <CardContent>
          {isAssignedEmpty ? (
            <PlantState title={t("plant.state.noAssignmentTitle")} description={t("plant.state.noAssignmentDesc")} />
          ) : null}
          {plants.isLoading ? <PlantTableSkeleton /> : null}
          {plants.isError ? (
            <PlantState
              title={t("plant.state.loadFailedTitle")}
              description={t("plant.state.loadFailedDesc")}
              action={
                <Button variant="outline" onClick={() => plants.refetch()}>
                  {tc("retry")}
                </Button>
              }
            />
          ) : null}
          {!plants.isLoading && !plants.isError && !isAssignedEmpty && plantItems.length === 0 ? (
            <PlantState title={t("plant.state.emptyTitle")} description={t("plant.state.emptyDesc")} />
          ) : null}
          {!plants.isLoading && !plants.isError && plantItems.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tc("code")}</TableHead>
                  <TableHead>{tc("name")}</TableHead>
                  <TableHead>{tc("createdAt")}</TableHead>
                  <TableHead className="text-right">{tc("actions")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {plantItems.map((plant) => (
                  <TableRow key={plant.id ?? plant.code}>
                    <TableCell className="font-medium">{plant.code}</TableCell>
                    <TableCell>{plant.name}</TableCell>
                    <TableCell>{plant.createdAt ? dt.dateTime(plant.createdAt) : "-"}</TableCell>
                    <TableCell className="text-right">
                      {canMutate ? (
                        <div className="flex justify-end gap-2">
                          <Button variant="outline" size="sm" onClick={() => openEditDialog(plant)}>
                            {tc("edit")}
                          </Button>
                          <Button variant="destructive" size="sm" onClick={() => setDeleteTarget(plant)}>
                            <Trash2 />
                            {tc("delete")}
                          </Button>
                        </div>
                      ) : (
                        <Badge variant="secondary">{t("plant.viewOnly")}</Badge>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : null}
        </CardContent>
      </Card>

      <Dialog open={dialogMode !== null} onOpenChange={(open) => !open && setDialogMode(null)}>
        <DialogContent className="top-4 max-h-[calc(100svh-2rem)] translate-y-0 overflow-y-auto sm:max-w-2xl">
          <form onSubmit={submitPlant} className="space-y-4">
            <DialogHeader>
              <DialogTitle>
                {dialogMode?.type === "edit" ? t("plant.dialog.editTitle") : t("plant.dialog.createTitle")}
              </DialogTitle>
              <DialogDescription>{t("plant.dialog.description")}</DialogDescription>
            </DialogHeader>
            {formError ? (
              <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">{formError}</p>
            ) : null}
            <div className="grid gap-2">
              <Label htmlFor="plant-code">{tc("code")}</Label>
              <Input
                id="plant-code"
                value={form.code}
                onChange={(event) => setForm((current) => ({ ...current, code: event.target.value }))}
                aria-invalid={Boolean(fieldErrors.code)}
                disabled={isSaving}
              />
              {fieldErrors.code ? <p className="text-destructive text-sm">{fieldErrors.code}</p> : null}
            </div>
            <div className="grid gap-2">
              <Label htmlFor="plant-name">{tc("name")}</Label>
              <Input
                id="plant-name"
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
                {t("plant.dialog.save")}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <AlertDialog open={deleteTarget !== null} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("plant.delete.title")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("plant.delete.description", { code: deleteTarget?.code ?? "" })}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deletePlant.isPending}>{tc("cancel")}</AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={confirmDelete} disabled={deletePlant.isPending}>
              {t("plant.delete.action")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

function PlantState({ title, description, action }: { title: string; description: string; action?: React.ReactNode }) {
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

function PlantTableSkeleton() {
  return (
    <div className="space-y-2">
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-10 w-full" />
    </div>
  );
}
