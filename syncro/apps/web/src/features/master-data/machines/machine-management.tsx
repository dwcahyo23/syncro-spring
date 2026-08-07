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
import { Calendar } from "@/components/ui/calendar";
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
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";
import { usePlantScope } from "@/features/plant-scope/plant-scope-store";
import type {
  ListMachineGroupsParams,
  ListMachinesParams,
  MachineGroupView,
  MachineRequest,
  MachineView,
  MachineViewStatus,
  PlantView,
} from "@/lib/api/generated/model";
import {
  getListMachineGroupsQueryKey,
  getListMachinesQueryKey,
  getListPlantsQueryKey,
  useCreateMachine,
  useDeleteMachine,
  useListMachineGroups,
  useListMachines,
  useListPlants,
  useUpdateMachine,
} from "@/lib/api/generated/syncro";
import { SyncroApiError } from "@/lib/api/orval-mutator";
import { useAuthUser } from "@/lib/auth/use-auth-user";

type MachineFormState = MachineRequest;
type DialogMode = { type: "create"; machine?: never } | { type: "edit"; machine: MachineView };
type ErrorResponse = { code: string; message: string; fieldErrors?: Record<string, string> };
type StatusFilter = "ALL" | MachineViewStatus;

const EMPTY_FORM: MachineFormState = {
  plantId: "",
  machineGroupId: "",
  code: "",
  name: "",
  status: "ACTIVE",
  brand: "",
  installedAt: "",
  notes: "",
};

export function MachineManagement() {
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
  const [selectedGroupId, setSelectedGroupId] = useState("ALL");
  const [selectedStatus, setSelectedStatus] = useState<StatusFilter>("ALL");
  const [groupSearch, setGroupSearch] = useState("");
  const [machineSearch, setMachineSearch] = useState("");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [sort, setSort] = useState("code,asc");
  const effectivePlantId = selectedPlantId || availablePlants[0]?.id || "";
  const groupParams = {
    plantId: effectivePlantId,
    search: groupSearch.trim() || undefined,
    page: 0,
    size: 100,
    sort: "name,asc",
  } satisfies ListMachineGroupsParams;
  const machineGroups = useListMachineGroups(groupParams, {
    query: {
      enabled: Boolean(effectivePlantId) && !isAssignedEmpty,
      queryKey: ["machine-groups", activePlantId, effectivePlantId, groupSearch.trim()],
    },
  });
  const groupItems = machineGroups.data?.data.items ?? [];
  const machineParams = {
    plantId: effectivePlantId || undefined,
    machineGroupId: selectedGroupId === "ALL" ? undefined : selectedGroupId,
    status: selectedStatus === "ALL" ? undefined : selectedStatus,
    search: machineSearch.trim() || undefined,
    page,
    size,
    sort,
  } satisfies ListMachinesParams;
  const machines = useListMachines(machineParams, {
    query: {
      enabled: Boolean(effectivePlantId) && !isAssignedEmpty,
      queryKey: [
        "machines",
        activePlantId,
        effectivePlantId,
        selectedGroupId,
        selectedStatus,
        machineSearch.trim(),
        page,
        size,
        sort,
      ],
    },
  });
  const createMachine = useCreateMachine({ mutation: { onSuccess: invalidateMachineData } });
  const updateMachine = useUpdateMachine({ mutation: { onSuccess: invalidateMachineData } });
  const deleteMachine = useDeleteMachine({ mutation: { onSuccess: invalidateMachineData } });
  const [dialogMode, setDialogMode] = useState<DialogMode | null>(null);
  const [form, setForm] = useState<MachineFormState>(EMPTY_FORM);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<MachineView | null>(null);
  const isSaving = createMachine.isPending || updateMachine.isPending;
  const machineItems = machines.data?.data.items ?? [];

  useEffect(() => {
    if (!effectivePlantId) {
      setSelectedPlantId("");
      return;
    }
    if (!availablePlants.some((plant) => plant.id === selectedPlantId)) {
      setSelectedPlantId(effectivePlantId);
    }
  }, [availablePlants, effectivePlantId, selectedPlantId]);

  useEffect(() => {
    if (selectedGroupId !== "ALL" && !groupItems.some((group) => group.id === selectedGroupId)) {
      setSelectedGroupId("ALL");
    }
  }, [groupItems, selectedGroupId]);

  function invalidateMachineData() {
    queryClient.invalidateQueries({
      queryKey: ["machines", activePlantId, effectivePlantId, selectedGroupId, selectedStatus],
    });
    queryClient.invalidateQueries({ queryKey: getListMachinesQueryKey(machineParams) });
    queryClient.invalidateQueries({ queryKey: getListMachineGroupsQueryKey(groupParams) });
    queryClient.invalidateQueries({ queryKey: getListPlantsQueryKey() });
  }

  function openCreateDialog() {
    setDialogMode({ type: "create" });
    setForm({ ...EMPTY_FORM, plantId: effectivePlantId, machineGroupId: firstGroupId(groupItems) });
    setFieldErrors({});
    setFormError(null);
  }

  function openEditDialog(machine: MachineView) {
    setDialogMode({ type: "edit", machine });
    setForm({
      plantId: machine.plantId ?? effectivePlantId,
      machineGroupId: machine.machineGroupId ?? "",
      code: machine.code ?? "",
      name: machine.name ?? "",
      status: machine.status ?? "ACTIVE",
      brand: machine.brand ?? "",
      installedAt: machine.installedAt ?? "",
      notes: machine.notes ?? "",
    });
    setFieldErrors({});
    setFormError(null);
  }

  async function submitMachine(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFieldErrors({});
    setFormError(null);
    const payload = normalizeForm(form);

    try {
      if (dialogMode?.type === "edit") {
        await updateMachine.mutateAsync({ machineId: dialogMode.machine.id ?? "", data: payload });
        toast.success("Machine updated.");
      } else {
        await createMachine.mutateAsync({ data: payload });
        toast.success("Machine created.");
      }
      setDialogMode(null);
    } catch (error) {
      const response = errorResponse(error);
      setFieldErrors(response?.fieldErrors ?? {});
      setFormError(response?.message ?? "Machine request failed.");
      toast.error(response?.message ?? "Machine request failed.");
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) {
      return;
    }

    try {
      await deleteMachine.mutateAsync({ machineId: deleteTarget.id ?? "" });
      toast.success("Machine deleted.");
      setDeleteTarget(null);
    } catch (error) {
      toast.error(errorResponse(error)?.message ?? "Machine delete failed.");
    }
  }

  const canCreate = canMutate && Boolean(effectivePlantId) && groupItems.length > 0 && !isAssignedEmpty;

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Machines</CardTitle>
          <CardDescription>Manage registered machine master data and manual ACTIVE/INACTIVE status.</CardDescription>
          <CardAction>
            {canMutate ? (
              <Button onClick={openCreateDialog} disabled={!canCreate}>
                Create machine
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
              value={machineSearch}
              onChange={(event) => setMachineSearch(event.target.value)}
              placeholder="Search machines"
              disabled={!effectivePlantId || isAssignedEmpty}
            />

            <MachineGroupSelect
              groups={groupItems}
              value={selectedGroupId}
              disabled={!effectivePlantId || machineGroups.isLoading || isAssignedEmpty}
              onChange={setSelectedGroupId}
              includeAll
              compact
              onSearchChange={setGroupSearch}
              search={groupSearch}
            />
            <StatusSelect
              value={selectedStatus}
              onChange={(value) => setSelectedStatus(value as StatusFilter)}
              includeAll
              compact
            />
          </div>
          {isAssignedEmpty ? (
            <MachineState title="No plant assignment" description="Your account has no assigned plant scope." />
          ) : null}
          {!isAssignedEmpty && !plants.isLoading && availablePlants.length === 0 ? (
            <MachineState title="No plants available" description="Create or assign a plant before adding machines." />
          ) : null}
          {!isAssignedEmpty &&
          effectivePlantId &&
          !machineGroups.isLoading &&
          groupItems.length === 0 &&
          !groupSearch.trim() ? (
            <MachineState
              title="No machine groups available"
              description="Create a machine group for selected plant before adding machines."
            />
          ) : null}
          {plants.isLoading || machineGroups.isLoading || machines.isLoading ? <MachineTableSkeleton /> : null}
          {plants.isError || machineGroups.isError || machines.isError ? (
            <MachineState
              title="Machines could not be loaded"
              description="Refresh page or contact administrator if access should be available."
              action={
                <Button
                  variant="outline"
                  onClick={() => void Promise.all([plants.refetch(), machineGroups.refetch(), machines.refetch()])}
                >
                  Retry
                </Button>
              }
            />
          ) : null}
          {!machines.isLoading &&
          !machines.isError &&
          effectivePlantId &&
          machineItems.length === 0 &&
          (groupItems.length > 0 || groupSearch.trim()) ? (
            <MachineState
              title={machineSearch || selectedGroupId !== "ALL" ? "No machines found" : "No machines yet"}
              description={
                machineSearch || selectedGroupId !== "ALL"
                  ? "Adjust your filters."
                  : "Create first registered machine for selected plant and group."
              }
            />
          ) : null}
          {!machines.isLoading && !machines.isError && machineItems.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>
                    <DataTableSortHeader title="Code" field="code" sort={sort} onSortChange={setSort} />
                  </TableHead>
                  <TableHead>
                    <DataTableSortHeader title="Name" field="name" sort={sort} onSortChange={setSort} />
                  </TableHead>
                  <TableHead>Plant</TableHead>
                  <TableHead>Group</TableHead>
                  <TableHead>Manual status</TableHead>
                  <TableHead>Brand</TableHead>
                  <TableHead>
                    <DataTableSortHeader title="Installed" field="installedAt" sort={sort} onSortChange={setSort} />
                  </TableHead>
                  <TableHead>
                    <DataTableSortHeader title="Updated" field="updatedAt" sort={sort} onSortChange={setSort} />
                  </TableHead>
                  <TableHead className="text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {machineItems.map((machine) => (
                  <TableRow key={machine.id ?? machine.code}>
                    <TableCell className="font-medium">{machine.code}</TableCell>
                    <TableCell>{machine.name || "-"}</TableCell>
                    <TableCell>
                      {machine.plantCode} · {machine.plantName}
                    </TableCell>
                    <TableCell>{machine.machineGroupName}</TableCell>
                    <TableCell>
                      <StatusBadge status={machine.status ?? "INACTIVE"} />
                    </TableCell>
                    <TableCell>{machine.brand || "-"}</TableCell>
                    <TableCell>{machine.installedAt ? formatDateOnly(machine.installedAt) : "-"}</TableCell>
                    <TableCell>{machine.updatedAt ? formatDateTime(machine.updatedAt) : "-"}</TableCell>
                    <TableCell className="text-right">
                      {canMutate ? (
                        <div className="flex justify-end gap-2">
                          <Button variant="outline" size="sm" onClick={() => openEditDialog(machine)}>
                            Edit
                          </Button>
                          <Button variant="destructive" size="sm" onClick={() => setDeleteTarget(machine)}>
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
          {!machines.isLoading && !machines.isError && machines.data?.data ? (
            <DataTablePagination
              page={page}
              size={size}
              totalElements={machines.data.data.totalElements}
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
        <DialogContent className="max-h-[90svh] overflow-y-auto sm:max-w-2xl">
          <form onSubmit={submitMachine} className="space-y-4">
            <DialogHeader>
              <DialogTitle>{dialogMode?.type === "edit" ? "Edit machine" : "Create machine"}</DialogTitle>
              <DialogDescription>
                Manual status is master data and does not reflect MQTT telemetry freshness.
              </DialogDescription>
            </DialogHeader>
            {formError ? (
              <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">{formError}</p>
            ) : null}
            <div className="grid gap-4 md:grid-cols-2">
              <div className="grid min-w-0 gap-2">
                <Label htmlFor="machine-plant">Plant</Label>
                <PlantSelect
                  plants={availablePlants}
                  value={form.plantId ?? ""}
                  disabled={isSaving || dialogMode?.type === "edit"}
                  onChange={(plantId) =>
                    setForm((current) => ({ ...current, plantId, machineGroupId: firstGroupId(groupItems) }))
                  }
                  triggerId="machine-plant"
                />
                {fieldErrors.plantId ? <p className="text-destructive text-sm">{fieldErrors.plantId}</p> : null}
              </div>
              <div className="grid min-w-0 gap-2">
                <Label htmlFor="machine-group">Machine group</Label>
                <MachineGroupSelect
                  groups={groupItems}
                  value={form.machineGroupId ?? ""}
                  disabled={isSaving || !form.plantId}
                  onChange={(machineGroupId) => setForm((current) => ({ ...current, machineGroupId }))}
                  triggerId="machine-group"
                />
                {fieldErrors.machineGroupId ? (
                  <p className="text-destructive text-sm">{fieldErrors.machineGroupId}</p>
                ) : null}
              </div>
            </div>
            <div className="grid gap-4 md:grid-cols-2">
              <div className="grid min-w-0 gap-2">
                <Label htmlFor="machine-code">Code</Label>
                <Input
                  id="machine-code"
                  value={form.code}
                  onChange={(event) => setForm((current) => ({ ...current, code: event.target.value }))}
                  aria-invalid={Boolean(fieldErrors.code)}
                  disabled={isSaving}
                />
                {fieldErrors.code ? <p className="text-destructive text-sm">{fieldErrors.code}</p> : null}
              </div>
              <div className="grid min-w-0 gap-2">
                <Label htmlFor="machine-status">Manual status</Label>
                <StatusSelect
                  value={form.status ?? "ACTIVE"}
                  onChange={(status) =>
                    setForm((current) => ({ ...current, status: status as MachineRequest["status"] }))
                  }
                  disabled={isSaving}
                  triggerId="machine-status"
                />
                {fieldErrors.status ? <p className="text-destructive text-sm">{fieldErrors.status}</p> : null}
              </div>
            </div>
            <div className="grid gap-4 md:grid-cols-2">
              <div className="grid min-w-0 gap-2">
                <Label htmlFor="machine-name">Name</Label>
                <Input
                  id="machine-name"
                  value={form.name ?? ""}
                  onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
                  disabled={isSaving}
                />
              </div>
              <div className="grid min-w-0 gap-2">
                <Label htmlFor="machine-brand">Brand</Label>
                <Input
                  id="machine-brand"
                  value={form.brand ?? ""}
                  onChange={(event) => setForm((current) => ({ ...current, brand: event.target.value }))}
                  disabled={isSaving}
                />
              </div>
            </div>
            <DatePickerField
              id="machine-installed"
              label="Installed date"
              value={form.installedAt ?? ""}
              onChange={(installedAt) => setForm((current) => ({ ...current, installedAt }))}
              disabled={isSaving}
            />
            <div className="grid gap-2">
              <Label htmlFor="machine-notes">Notes</Label>
              <Textarea
                id="machine-notes"
                value={form.notes ?? ""}
                onChange={(event) => setForm((current) => ({ ...current, notes: event.target.value }))}
                disabled={isSaving}
              />
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setDialogMode(null)} disabled={isSaving}>
                Cancel
              </Button>
              <Button type="submit" disabled={isSaving || !form.plantId || !form.machineGroupId}>
                {isSaving ? <Loader2Icon className="animate-spin" /> : null}
                Save machine
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <AlertDialog open={deleteTarget !== null} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete machine?</AlertDialogTitle>
            <AlertDialogDescription>
              This removes {deleteTarget?.code}. Future sparepart, telemetry, responsibility, alert, or audit records
              may block deletion.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleteMachine.isPending}>Cancel</AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={confirmDelete} disabled={deleteMachine.isPending}>
              Delete machine
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

function MachineGroupSelect({
  groups,
  value,
  disabled,
  onChange,
  triggerId,
  includeAll,
  compact,
  onSearchChange,
  search,
}: {
  groups: MachineGroupView[];
  value: string;
  disabled?: boolean;
  onChange: (value: string) => void;
  triggerId?: string;
  includeAll?: boolean;
  compact?: boolean;
  onSearchChange?: (value: string) => void;
  search?: string;
}) {
  return (
    <Select value={value} onValueChange={onChange} disabled={disabled}>
      <SelectTrigger id={triggerId} className={compact ? "w-full min-w-0" : "w-full min-w-0 sm:min-w-52"}>
        <SelectValue placeholder="Select machine group">
          {groups.find((g) => g.id === value)?.name ?? (value === "ALL" ? "All groups" : "Select machine group")}
        </SelectValue>
      </SelectTrigger>
      <SelectContent>
        {onSearchChange !== undefined ? (
          <div className="p-2">
            <Input
              value={search}
              placeholder="Search groups..."
              onChange={(e) => onSearchChange(e.target.value)}
              onClick={(e) => e.stopPropagation()}
              onKeyDown={(e) => e.stopPropagation()}
            />
          </div>
        ) : null}
        {includeAll ? <SelectItem value="ALL">All groups</SelectItem> : null}
        {groups.map((group) => (
          <SelectItem key={group.id ?? group.name} value={group.id ?? ""}>
            {group.name}
          </SelectItem>
        ))}
        {groups.length === 0 ? (
          <p className="px-2 py-3 text-center text-sm text-muted-foreground">No groups found</p>
        ) : null}
      </SelectContent>
    </Select>
  );
}

function StatusSelect({
  value,
  onChange,
  disabled,
  triggerId,
  includeAll,
  compact,
}: {
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  triggerId?: string;
  includeAll?: boolean;
  compact?: boolean;
}) {
  return (
    <Select value={value} onValueChange={onChange} disabled={disabled}>
      <SelectTrigger id={triggerId} className={compact ? "w-full min-w-0" : "w-full min-w-0 sm:min-w-40"}>
        <SelectValue placeholder="Status" />
      </SelectTrigger>
      <SelectContent>
        {includeAll ? <SelectItem value="ALL">All statuses</SelectItem> : null}
        <SelectItem value="ACTIVE">ACTIVE</SelectItem>
        <SelectItem value="INACTIVE">INACTIVE</SelectItem>
      </SelectContent>
    </Select>
  );
}

function DatePickerField({
  id,
  label,
  value,
  onChange,
  disabled,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
}) {
  const selected = value ? new Date(`${value}T00:00:00Z`) : undefined;

  return (
    <div className="grid gap-2">
      <Label htmlFor={id}>{label}</Label>
      <Popover>
        <PopoverTrigger asChild>
          <Button id={id} type="button" variant="outline" className="justify-start font-normal" disabled={disabled}>
            {value ? formatDateOnly(value) : "Select date"}
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-auto p-0" align="start">
          <Calendar
            mode="single"
            selected={selected}
            onSelect={(date) => onChange(date ? date.toISOString().slice(0, 10) : "")}
            defaultMonth={selected}
          />
        </PopoverContent>
      </Popover>
    </div>
  );
}

function StatusBadge({ status }: { status: MachineViewStatus }) {
  return <Badge variant={status === "ACTIVE" ? "default" : "secondary"}>Manual {status}</Badge>;
}

function MachineState({
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

function MachineTableSkeleton() {
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

function firstGroupId(groups: MachineGroupView[]) {
  return groups[0]?.id ?? "";
}

function normalizeForm(form: MachineFormState): MachineRequest {
  return {
    plantId: form.plantId,
    machineGroupId: form.machineGroupId,
    code: form.code,
    name: emptyToUndefined(form.name),
    status: form.status,
    brand: emptyToUndefined(form.brand),
    installedAt: emptyToUndefined(form.installedAt),
    notes: emptyToUndefined(form.notes),
  };
}

function emptyToUndefined(value: string | undefined) {
  return value?.trim() ? value : undefined;
}

function errorResponse(error: unknown): ErrorResponse | null {
  if (!(error instanceof SyncroApiError) || !error.payload || typeof error.payload !== "object") {
    return null;
  }
  const payload = error.payload as ErrorResponse;
  return typeof payload.code === "string" && typeof payload.message === "string" ? payload : null;
}

function formatDateOnly(value: string) {
  return new Intl.DateTimeFormat("en", { dateStyle: "medium" }).format(new Date(`${value}T00:00:00Z`));
}

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}
