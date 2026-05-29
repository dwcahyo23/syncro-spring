"use client";

import { type FormEvent, useEffect, useMemo, useState } from "react";

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
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { usePlantScope } from "@/features/plant-scope/plant-scope-store";
import type {
  ListMachineGroupsParams,
  MachineGroupRequest,
  MachineGroupView,
  PlantView,
} from "@/lib/api/generated/model";
import {
  getListMachineGroupsQueryKey,
  getListPlantsQueryKey,
  useCreateMachineGroup,
  useDeleteMachineGroup,
  useListMachineGroups,
  useListPlants,
  useUpdateMachineGroup,
} from "@/lib/api/generated/syncro";
import { SyncroApiError } from "@/lib/api/orval-mutator";
import { useAuthUser } from "@/lib/auth/use-auth-user";

type MachineGroupFormState = MachineGroupRequest;
type DialogMode = { type: "create"; group?: never } | { type: "edit"; group: MachineGroupView };
type ErrorResponse = { code: string; message: string; fieldErrors?: Record<string, string> };

const EMPTY_FORM: MachineGroupFormState = { plantId: "", name: "" };

export function MachineGroupManagement() {
  const user = useAuthUser();
  const plantScope = usePlantScope();
  const scope = plantScope.scope;
  const activePlantId = plantScope.activePlantId;
  const queryClient = useQueryClient();
  const isAssignedEmpty = scope?.mode === "EMPTY";
  const canMutate = user?.applicationRole === "SUPER_ADMIN" || user?.applicationRole === "MANAGE";
  const plants = useListPlants({ query: { enabled: Boolean(scope) && !isAssignedEmpty } });
  const plantItems = plants.data?.data.items ?? [];
  const availablePlants = useMemo(() => permittedPlants(plantItems, scope), [plantItems, scope]);
  const [selectedPlantId, setSelectedPlantId] = useState("");
  const [search, setSearch] = useState("");
  const effectivePlantId = selectedPlantId || availablePlants[0]?.id || "";
  const groupParams = {
    plantId: effectivePlantId,
    search: search.trim() || undefined,
    page: 0,
    size: 100,
    sort: "name,asc",
  } satisfies ListMachineGroupsParams;
  const machineGroups = useListMachineGroups(groupParams, {
    query: {
      enabled: Boolean(effectivePlantId) && !isAssignedEmpty,
      queryKey: ["machine-groups", activePlantId, effectivePlantId, search.trim()],
    },
  });
  const createGroup = useCreateMachineGroup({
    mutation: { onSuccess: () => invalidateMachineGroupData(effectivePlantId) },
  });
  const updateGroup = useUpdateMachineGroup({
    mutation: { onSuccess: () => invalidateMachineGroupData(effectivePlantId) },
  });
  const deleteGroup = useDeleteMachineGroup({
    mutation: { onSuccess: () => invalidateMachineGroupData(effectivePlantId) },
  });
  const [dialogMode, setDialogMode] = useState<DialogMode | null>(null);
  const [form, setForm] = useState<MachineGroupFormState>(EMPTY_FORM);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<MachineGroupView | null>(null);
  const isSaving = createGroup.isPending || updateGroup.isPending;
  const groupItems = machineGroups.data?.data.items ?? [];

  useEffect(() => {
    if (!effectivePlantId) {
      setSelectedPlantId("");
      return;
    }
    if (!availablePlants.some((plant) => plant.id === selectedPlantId)) {
      setSelectedPlantId(effectivePlantId);
    }
  }, [availablePlants, effectivePlantId, selectedPlantId]);

  function invalidateMachineGroupData(plantId: string) {
    queryClient.invalidateQueries({ queryKey: ["machine-groups", activePlantId, plantId] });
    queryClient.invalidateQueries({ queryKey: getListMachineGroupsQueryKey(groupParams) });
    queryClient.invalidateQueries({ queryKey: getListPlantsQueryKey() });
  }

  function openCreateDialog() {
    setDialogMode({ type: "create" });
    setForm({ plantId: effectivePlantId, name: "" });
    setFieldErrors({});
    setFormError(null);
  }

  function openEditDialog(group: MachineGroupView) {
    setDialogMode({ type: "edit", group });
    setForm({ plantId: group.plantId ?? effectivePlantId, name: group.name ?? "" });
    setFieldErrors({});
    setFormError(null);
  }

  async function submitMachineGroup(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFieldErrors({});
    setFormError(null);

    try {
      if (dialogMode?.type === "edit") {
        await updateGroup.mutateAsync({ machineGroupId: dialogMode.group.id ?? "", data: form });
        toast.success("Machine group updated.");
      } else {
        await createGroup.mutateAsync({ data: form });
        toast.success("Machine group created.");
      }
      setDialogMode(null);
    } catch (error) {
      const response = errorResponse(error);
      setFieldErrors(response?.fieldErrors ?? {});
      setFormError(response?.message ?? "Machine group request failed.");
      toast.error(response?.message ?? "Machine group request failed.");
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) {
      return;
    }

    try {
      await deleteGroup.mutateAsync({ machineGroupId: deleteTarget.id ?? "" });
      toast.success("Machine group deleted.");
      setDeleteTarget(null);
    } catch (error) {
      toast.error(errorResponse(error)?.message ?? "Machine group delete failed.");
    }
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Machine Groups</CardTitle>
          <CardDescription>Manage plant-scoped process lines such as Forming.</CardDescription>
          <CardAction>
            {canMutate ? (
              <Button onClick={openCreateDialog} disabled={!effectivePlantId || isAssignedEmpty}>
                Create machine group
              </Button>
            ) : (
              <Badge variant="secondary">Read-only</Badge>
            )}
          </CardAction>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-3 rounded-lg border p-3 sm:grid-cols-[repeat(auto-fit,14rem)] sm:justify-start">
            <PlantSelect
              plants={availablePlants}
              value={effectivePlantId}
              disabled={!availablePlants.length || plants.isLoading || isAssignedEmpty}
              onChange={setSelectedPlantId}
              compact
            />
            <Input
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Search groups"
              disabled={!effectivePlantId || isAssignedEmpty}
            />
          </div>
          {isAssignedEmpty ? (
            <MachineGroupState title="No plant assignment" description="Your account has no assigned plant scope." />
          ) : null}
          {!isAssignedEmpty && !plants.isLoading && availablePlants.length === 0 ? (
            <MachineGroupState
              title="No plants available"
              description="Create or assign a plant before adding machine groups."
            />
          ) : null}
          {plants.isLoading || machineGroups.isLoading ? <MachineGroupTableSkeleton /> : null}
          {plants.isError || machineGroups.isError ? (
            <MachineGroupState
              title="Machine groups could not be loaded"
              description="Refresh page or contact administrator if access should be available."
              action={
                <Button variant="outline" onClick={() => void Promise.all([plants.refetch(), machineGroups.refetch()])}>
                  Retry
                </Button>
              }
            />
          ) : null}
          {!machineGroups.isLoading && !machineGroups.isError && effectivePlantId && groupItems.length === 0 ? (
            <MachineGroupState
              title="No machine groups yet"
              description="Create first process line for selected plant."
            />
          ) : null}
          {!machineGroups.isLoading && !machineGroups.isError && groupItems.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Plant</TableHead>
                  <TableHead>Created</TableHead>
                  <TableHead className="text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {groupItems.map((group) => (
                  <TableRow key={group.id ?? group.name}>
                    <TableCell className="font-medium">{group.name}</TableCell>
                    <TableCell>
                      {group.plantCode} · {group.plantName}
                    </TableCell>
                    <TableCell>{group.createdAt ? formatDate(group.createdAt) : "-"}</TableCell>
                    <TableCell className="text-right">
                      {canMutate ? (
                        <div className="flex justify-end gap-2">
                          <Button variant="outline" size="sm" onClick={() => openEditDialog(group)}>
                            Edit
                          </Button>
                          <Button variant="destructive" size="sm" onClick={() => setDeleteTarget(group)}>
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
        <DialogContent className="max-h-[90svh] overflow-y-auto sm:max-w-lg">
          <form onSubmit={submitMachineGroup} className="space-y-4">
            <DialogHeader>
              <DialogTitle>{dialogMode?.type === "edit" ? "Edit machine group" : "Create machine group"}</DialogTitle>
              <DialogDescription>
                Names are unique within selected plant and may repeat across plants.
              </DialogDescription>
            </DialogHeader>
            {formError ? (
              <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">{formError}</p>
            ) : null}
            <div className="grid gap-2">
              <Label htmlFor="machine-group-plant">Plant</Label>
              <PlantSelect
                plants={availablePlants}
                value={form.plantId ?? ""}
                disabled={isSaving || dialogMode?.type === "edit"}
                onChange={(plantId) => setForm((current) => ({ ...current, plantId }))}
                triggerId="machine-group-plant"
              />
              {fieldErrors.plantId ? <p className="text-destructive text-sm">{fieldErrors.plantId}</p> : null}
            </div>
            <div className="grid gap-2">
              <Label htmlFor="machine-group-name">Name</Label>
              <Input
                id="machine-group-name"
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
              <Button type="submit" disabled={isSaving || !form.plantId}>
                {isSaving ? <Loader2Icon className="animate-spin" /> : null}
                Save machine group
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <AlertDialog open={deleteTarget !== null} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete machine group?</AlertDialogTitle>
            <AlertDialogDescription>
              This removes {deleteTarget?.name}. Deletion succeeds while no dependent machines exist; future dependent
              machines may block it.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleteGroup.isPending}>Cancel</AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={confirmDelete} disabled={deleteGroup.isPending}>
              Delete machine group
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

function PlantSelect({
  plants,
  value,
  disabled,
  onChange,
  triggerId,
  compact,
}: {
  plants: PlantView[];
  value: string;
  disabled?: boolean;
  onChange: (value: string) => void;
  triggerId?: string;
  compact?: boolean;
}) {
  return (
    <Select value={value} onValueChange={onChange} disabled={disabled}>
      <SelectTrigger id={triggerId} className={compact ? "w-full min-w-0" : "w-full min-w-0 sm:min-w-52"}>
        <SelectValue placeholder="Select plant" />
      </SelectTrigger>
      <SelectContent>
        {plants.map((plant) => (
          <SelectItem key={plant.id ?? plant.code} value={plant.id ?? ""}>
            {plant.code} · {plant.name}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

function MachineGroupState({
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

function MachineGroupTableSkeleton() {
  return (
    <div className="space-y-2">
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-10 w-full" />
    </div>
  );
}

function permittedPlants(plants: PlantView[], scope: ReturnType<typeof usePlantScope>["scope"]) {
  if (!scope || scope.mode === "EMPTY") {
    return [];
  }
  if (scope.mode === "UNRESTRICTED") {
    return plants;
  }
  const assignedIds = new Set((scope.availablePlants ?? []).map((plant) => plant.id));
  return plants.filter((plant) => plant.id && assignedIds.has(plant.id));
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
