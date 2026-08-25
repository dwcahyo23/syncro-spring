"use client";

import { type FormEvent, useEffect, useMemo, useRef, useState } from "react";

import { useQueryClient } from "@tanstack/react-query";
import { Loader2Icon, Trash2, TriangleAlertIcon } from "lucide-react";
import { toast } from "sonner";

import { ShiftConfigEditor, type ShiftWindowInput } from "@/components/syncro/shift-config-editor";
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
import { DataTablePagination } from "@/components/ui/data-table-pagination";
import { DataTableSortHeader } from "@/components/ui/data-table-sort-header";
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
  getGetMachineGroupShiftConfigQueryKey,
  getListMachineGroupsQueryKey,
  getListPlantsQueryKey,
  useAssignMachineGroupSection,
  useClearMachineGroupSection,
  useCreateMachineGroup,
  useDeleteMachineGroup,
  useGetMachineGroupShiftConfig,
  useListMachineGroups,
  useListPlants,
  useListSections,
  useUpdateMachineGroup,
  useUpdateMachineGroupShiftConfig,
} from "@/lib/api/generated/syncro";
import { SyncroApiError } from "@/lib/api/orval-mutator";
import { useAuthUser } from "@/lib/auth/use-auth-user";

type MachineGroupFormState = MachineGroupRequest;
type DialogMode = { type: "create"; group?: never } | { type: "edit"; group: MachineGroupView };
type ErrorResponse = { code: string; message: string; fieldErrors?: Record<string, string> };

const EMPTY_FORM: MachineGroupFormState = { plantId: "", name: "" };

function toShiftWindowInput(window: { startTime?: string; endTime?: string }): ShiftWindowInput {
  return { startTime: window.startTime ?? "", endTime: window.endTime ?? "" };
}

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
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [sort, setSort] = useState("name,asc");
  const effectivePlantId = selectedPlantId || availablePlants[0]?.id || "";
  const groupParams = {
    plantId: effectivePlantId,
    search: search.trim() || undefined,
    page,
    size,
    sort,
  } satisfies ListMachineGroupsParams;
  const machineGroups = useListMachineGroups(groupParams, {
    query: {
      enabled: Boolean(effectivePlantId) && !isAssignedEmpty,
      queryKey: ["machine-groups", activePlantId, effectivePlantId, search.trim(), page, size, sort],
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
  const sections = useListSections(
    { plantId: effectivePlantId || undefined, includeInactive: true },
    { query: { enabled: Boolean(effectivePlantId) && !isAssignedEmpty, queryKey: ["sections", effectivePlantId] } },
  );
  const sectionItems = sections.data?.data.items ?? [];
  const assignSection = useAssignMachineGroupSection({
    mutation: { onSuccess: () => invalidateMachineGroupData(effectivePlantId) },
  });
  const clearSection = useClearMachineGroupSection({
    mutation: { onSuccess: () => invalidateMachineGroupData(effectivePlantId) },
  });
  const [sectionTarget, setSectionTarget] = useState<MachineGroupView | null>(null);
  const [selectedSectionId, setSelectedSectionId] = useState("");
  const [dialogMode, setDialogMode] = useState<DialogMode | null>(null);
  const [form, setForm] = useState<MachineGroupFormState>(EMPTY_FORM);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<MachineGroupView | null>(null);
  const isSaving = createGroup.isPending || updateGroup.isPending;
  const groupItems = machineGroups.data?.data.items ?? [];
  const editingGroupId = dialogMode?.type === "edit" ? (dialogMode.group.id ?? "") : "";
  const groupShiftConfig = useGetMachineGroupShiftConfig(editingGroupId, {
    query: { enabled: Boolean(editingGroupId) },
  });
  const [groupShifts, setGroupShifts] = useState<ShiftWindowInput[]>([]);
  const [shiftError, setShiftError] = useState<string | null>(null);
  const saveGroupShifts = useUpdateMachineGroupShiftConfig();
  const groupShiftsDirtyRef = useRef(false);

  useEffect(() => {
    // A background refetch (window focus, invalidation) must never clobber in-progress edits.
    if (groupShiftsDirtyRef.current) {
      return;
    }
    setGroupShifts((groupShiftConfig.data?.data.shifts ?? []).map(toShiftWindowInput));
  }, [groupShiftConfig.data]);

  function updateGroupShiftRows(next: ShiftWindowInput[]) {
    groupShiftsDirtyRef.current = true;
    setGroupShifts(next);
  }

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
    groupShiftsDirtyRef.current = false;
    setGroupShifts([]);
    setShiftError(null);
  }

  function openEditDialog(group: MachineGroupView) {
    setDialogMode({ type: "edit", group });
    setForm({ plantId: group.plantId ?? effectivePlantId, name: group.name ?? "" });
    setFieldErrors({});
    setFormError(null);
    groupShiftsDirtyRef.current = false;
    setGroupShifts((groupShiftConfig.data?.data.shifts ?? []).map(toShiftWindowInput));
    setShiftError(null);
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

  function shiftErrorMessage(response: ErrorResponse | null): string {
    return response?.fieldErrors?.shifts ?? response?.message ?? "Shift schedule request failed.";
  }

  async function submitShiftSchedule() {
    if (dialogMode?.type !== "edit") {
      return;
    }
    setShiftError(null);
    if (groupShifts.some((window) => window.startTime === "" || window.endTime === "")) {
      setShiftError("Each shift needs both a start and an end time.");
      return;
    }

    try {
      await saveGroupShifts.mutateAsync({
        machineGroupId: dialogMode.group.id ?? "",
        data: { shifts: groupShifts },
      });
      groupShiftsDirtyRef.current = false;
      queryClient.invalidateQueries({
        queryKey: getGetMachineGroupShiftConfigQueryKey(dialogMode.group.id ?? ""),
      });
      // Machines inheriting this schedule cache their resolved config; refresh them too.
      queryClient.invalidateQueries({
        predicate: (query) => String(query.queryKey[0] ?? "").includes("/shift-config"),
      });
      toast.success("Shift schedule saved.");
    } catch (error) {
      const response = errorResponse(error);
      setShiftError(shiftErrorMessage(response));
      toast.error(shiftErrorMessage(response));
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

  function openSectionDialog(group: MachineGroupView) {
    setSectionTarget(group);
    setSelectedSectionId(group.sectionId ?? "");
  }

  async function submitSectionAssignment() {
    if (!sectionTarget) {
      return;
    }
    try {
      if (selectedSectionId) {
        await assignSection.mutateAsync({
          machineGroupId: sectionTarget.id ?? "",
          data: { sectionId: selectedSectionId },
        });
        toast.success("Section assigned.");
      } else if (sectionTarget.sectionId) {
        await clearSection.mutateAsync({ machineGroupId: sectionTarget.id ?? "" });
        toast.success("Section assignment cleared.");
      }
      setSectionTarget(null);
    } catch (error) {
      const response = errorResponse(error);
      if (response?.code === "SECTION_REASSIGNMENT_REJECTED") {
        toast.error("This group already belongs to another section. Clear the current section first.");
      } else if (response?.code === "SECTION_PLANT_MISMATCH") {
        toast.error("The selected section belongs to a different plant.");
      } else {
        toast.error(response?.message ?? "Section assignment failed.");
      }
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
          <div className="grid gap-3 rounded-lg border p-3 sm:grid-cols-[repeat(auto-fill,minmax(14rem,14rem))] sm:justify-start">
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
                  <TableHead>
                    <DataTableSortHeader title="Name" field="name" sort={sort} onSortChange={setSort} />
                  </TableHead>
                  <TableHead>Plant</TableHead>
                  <TableHead>Section</TableHead>
                  <TableHead>
                    <DataTableSortHeader title="Created" field="createdAt" sort={sort} onSortChange={setSort} />
                  </TableHead>
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
                    <TableCell>
                      {group.sectionCode ? (
                        <Badge variant="outline">
                          {group.sectionCode}
                          {group.sectionName ? ` · ${group.sectionName}` : ""}
                        </Badge>
                      ) : (
                        "-"
                      )}
                    </TableCell>
                    <TableCell>{group.createdAt ? formatDate(group.createdAt) : "-"}</TableCell>
                    <TableCell className="text-right">
                      {canMutate ? (
                        <div className="flex justify-end gap-2">
                          <Button variant="outline" size="sm" onClick={() => openSectionDialog(group)}>
                            Section
                          </Button>
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
          {!machineGroups.isLoading && !machineGroups.isError && machineGroups.data?.data ? (
            <DataTablePagination
              page={page}
              size={size}
              totalElements={machineGroups.data.data.totalElements}
              onPageChange={setPage}
              onSizeChange={(newSize) => {
                setSize(newSize);
                setPage(0);
              }}
            />
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
            {dialogMode?.type === "edit" ? (
              <fieldset className="grid gap-2 rounded-lg border p-3">
                <legend className="px-1 font-medium text-sm">Shift configuration</legend>
                {groupShiftConfig.status === "pending" ? (
                  <p className="text-muted-foreground text-sm">Loading shift schedule…</p>
                ) : null}
                {groupShiftConfig.status === "error" ? (
                  <p className="text-destructive text-sm">Shift schedule could not be loaded.</p>
                ) : null}
                {groupShiftConfig.status === "success" ? (
                  <>
                    <ShiftConfigEditor
                      value={groupShifts}
                      onChange={updateGroupShiftRows}
                      error={shiftError ?? undefined}
                      readOnly={!canMutate || saveGroupShifts.isPending}
                    />
                    {canMutate ? (
                      <Button
                        type="button"
                        variant="outline"
                        className="w-fit"
                        onClick={() => void submitShiftSchedule()}
                        disabled={saveGroupShifts.isPending}
                      >
                        {saveGroupShifts.isPending ? <Loader2Icon className="animate-spin" /> : null}
                        Save shift schedule
                      </Button>
                    ) : null}
                  </>
                ) : null}
              </fieldset>
            ) : null}
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

      <Dialog open={sectionTarget !== null} onOpenChange={(open) => !open && setSectionTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Assign section</DialogTitle>
            <DialogDescription>
              {sectionTarget?.name} belongs to exactly one section. Reassignment to a different section is rejected —
              clear the current section first.
            </DialogDescription>
          </DialogHeader>
          <div className="grid gap-2">
            <Label htmlFor="group-section">Section</Label>
            <Select value={selectedSectionId} onValueChange={setSelectedSectionId}>
              <SelectTrigger id="group-section">
                <SelectValue placeholder="No section (unassigned)" />
              </SelectTrigger>
              <SelectContent>
                {sectionItems.map((section) => (
                  <SelectItem key={section.id} value={section.id ?? ""}>
                    {section.code} — {section.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setSectionTarget(null)}>
              Cancel
            </Button>
            <Button
              type="button"
              onClick={() => void submitSectionAssignment()}
              disabled={assignSection.isPending || clearSection.isPending}
            >
              {assignSection.isPending || clearSection.isPending ? <Loader2Icon className="animate-spin" /> : null}
              Save assignment
            </Button>
          </DialogFooter>
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
