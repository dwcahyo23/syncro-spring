"use client";

import { type FormEvent, useMemo, useState } from "react";

import { useQueryClient } from "@tanstack/react-query";
import { Loader2Icon, Trash2, TriangleAlertIcon } from "lucide-react";
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
import type { PlantRequest, PlantView } from "@/lib/api/generated/model";
import { useCreate, useDelete, useList, useUpdate } from "@/lib/api/generated/syncro";
import { SyncroApiError } from "@/lib/api/orval-mutator";
import { useAuthUser } from "@/lib/auth/use-auth-user";

type PlantFormState = PlantRequest;

type ErrorResponse = {
  code: string;
  message: string;
  fieldErrors?: Record<string, string>;
};

type DialogMode = { type: "create"; plant?: never } | { type: "edit"; plant: PlantView };

const EMPTY_FORM: PlantFormState = { code: "", name: "" };

export function PlantManagement() {
  const user = useAuthUser();
  const plantScope = usePlantScope();
  const scope = plantScope.scope;
  const activePlantId = plantScope.activePlantId;
  const queryClient = useQueryClient();
  const plantsQueryKey = ["plants", activePlantId] as const;
  const plants = useList({ query: { queryKey: plantsQueryKey } });
  const invalidatePlants = () => queryClient.invalidateQueries({ queryKey: plantsQueryKey });
  const createPlant = useCreate({ mutation: { onSuccess: invalidatePlants } });
  const updatePlant = useUpdate({ mutation: { onSuccess: invalidatePlants } });
  const deletePlant = useDelete({ mutation: { onSuccess: invalidatePlants } });
  const [dialogMode, setDialogMode] = useState<DialogMode | null>(null);
  const [form, setForm] = useState<PlantFormState>(EMPTY_FORM);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<PlantView | null>(null);

  const canMutate = user?.applicationRole === "SUPER_ADMIN" || user?.applicationRole === "MANAGE";
  const isAssignedEmpty = scope?.mode === "EMPTY";
  const plantItems = plants.data?.data.items ?? [];
  const isSaving = createPlant.isPending || updatePlant.isPending;

  const scopeLabel = useMemo(() => {
    if (!scope) {
      return "Loading plant scope";
    }
    if (scope.mode === "UNRESTRICTED") {
      return "All plants";
    }
    if (scope.mode === "EMPTY") {
      return "No plant assigned";
    }
    return activePlantId === "all" ? "Assigned plants" : "Active plant";
  }, [activePlantId, scope]);

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
        toast.success("Plant updated.");
      } else {
        await createPlant.mutateAsync({ data: form });
        toast.success("Plant created.");
      }
      setDialogMode(null);
    } catch (error) {
      const response = errorResponse(error);
      setFieldErrors(response?.fieldErrors ?? {});
      setFormError(response?.message ?? "Plant request failed.");
      toast.error(response?.message ?? "Plant request failed.");
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) {
      return;
    }

    try {
      await deletePlant.mutateAsync({ plantId: deleteTarget.id ?? "" });
      toast.success("Plant deleted.");
      setDeleteTarget(null);
    } catch (error) {
      toast.error(errorResponse(error)?.message ?? "Plant delete failed.");
    }
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Plants</CardTitle>
          <CardDescription>Manage plant records used by machine setup and plant-scoped access.</CardDescription>
          <CardAction className="flex items-center gap-2">
            <Badge variant="outline">{scopeLabel}</Badge>
            {canMutate ? (
              <Button onClick={openCreateDialog} disabled={isAssignedEmpty}>
                Create plant
              </Button>
            ) : (
              <Badge variant="secondary">Read-only</Badge>
            )}
          </CardAction>
        </CardHeader>
        <CardContent>
          {isAssignedEmpty ? (
            <PlantState title="No plant assignment" description="Your account has no assigned plant scope." />
          ) : null}
          {plants.isLoading ? <PlantTableSkeleton /> : null}
          {plants.isError ? (
            <PlantState
              title="Plants could not be loaded"
              description="Refresh page or contact administrator if access should be available."
              action={
                <Button variant="outline" onClick={() => plants.refetch()}>
                  Retry
                </Button>
              }
            />
          ) : null}
          {!plants.isLoading && !plants.isError && !isAssignedEmpty && plantItems.length === 0 ? (
            <PlantState title="No plants yet" description="Create first plant before adding machine groups." />
          ) : null}
          {!plants.isLoading && !plants.isError && plantItems.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Code</TableHead>
                  <TableHead>Name</TableHead>
                  <TableHead>Created</TableHead>
                  <TableHead className="text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {plantItems.map((plant) => (
                  <TableRow key={plant.id ?? plant.code}>
                    <TableCell className="font-medium">{plant.code}</TableCell>
                    <TableCell>{plant.name}</TableCell>
                    <TableCell>{plant.createdAt ? formatDate(plant.createdAt) : "-"}</TableCell>
                    <TableCell className="text-right">
                      {canMutate ? (
                        <div className="flex justify-end gap-2">
                          <Button variant="outline" size="sm" onClick={() => openEditDialog(plant)}>
                            Edit
                          </Button>
                          <Button variant="destructive" size="sm" onClick={() => setDeleteTarget(plant)}>
                            <Trash2 />
                            Delete
                          </Button>
                        </div>
                      ) : (
                        <Badge variant="secondary">View only</Badge>
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
        <DialogContent>
          <form onSubmit={submitPlant} className="space-y-4">
            <DialogHeader>
              <DialogTitle>{dialogMode?.type === "edit" ? "Edit plant" : "Create plant"}</DialogTitle>
              <DialogDescription>Plant codes are normalized by backend and must be unique.</DialogDescription>
            </DialogHeader>
            {formError ? (
              <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">{formError}</p>
            ) : null}
            <div className="grid gap-2">
              <Label htmlFor="plant-code">Code</Label>
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
              <Label htmlFor="plant-name">Name</Label>
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
                Cancel
              </Button>
              <Button type="submit" disabled={isSaving}>
                {isSaving ? <Loader2Icon className="animate-spin" /> : null}
                Save plant
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <AlertDialog open={deleteTarget !== null} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete plant?</AlertDialogTitle>
            <AlertDialogDescription>
              This removes {deleteTarget?.code} and existing plant assignments tied to it.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deletePlant.isPending}>Cancel</AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={confirmDelete} disabled={deletePlant.isPending}>
              Delete plant
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

function errorResponse(error: unknown): ErrorResponse | null {
  if (!(error instanceof SyncroApiError) || !error.payload || typeof error.payload !== "object") {
    return null;
  }
  const payload = error.payload as ErrorResponse;
  return typeof payload.code === "string" && typeof payload.message === "string" ? payload : null;
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}
