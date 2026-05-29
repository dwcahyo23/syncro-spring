"use client";

import { type FormEvent, useEffect, useMemo, useState } from "react";

import { useQueryClient } from "@tanstack/react-query";
import { Loader2Icon, Trash2, TriangleAlertIcon } from "lucide-react";
import { toast } from "sonner";

import {
  AlertDialog,
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
  InstallationRequest,
  InstallationUpdateRequest,
  InstallationView,
  MachineView,
  PlantView,
  SparepartView,
} from "@/lib/api/generated/model";
import {
  getListMachineSparepartInstallationsQueryKey,
  getListMachinesQueryKey,
  getListSparepartsQueryKey,
  useCreateMachineSparepartInstallation,
  useDeleteMachineSparepartInstallation,
  useListMachineSparepartInstallations,
  useListMachines,
  useListPlants,
  useListSpareparts,
  useUpdateMachineSparepartInstallation,
} from "@/lib/api/generated/syncro";
import { SyncroApiError } from "@/lib/api/orval-mutator";
import { useAuthUser } from "@/lib/auth/use-auth-user";

type DialogMode = { type: "create"; installation?: never } | { type: "edit"; installation: InstallationView };
type ErrorResponse = { code: string; message: string; fieldErrors?: Record<string, string> };
type Filters = { plantId?: string; machineId?: string; sparepartId?: string };
type InstallationForm = {
  machineId: string;
  sparepartId: string;
  expectedProductionCount: string;
  baselineCounter: string;
  thresholdPercentage: string;
};

const ALL = "__all__";
const EMPTY_FORM: InstallationForm = {
  machineId: "",
  sparepartId: "",
  expectedProductionCount: "",
  baselineCounter: "0",
  thresholdPercentage: "90",
};

export function InstallationManagement() {
  const user = useAuthUser();
  const plantScope = usePlantScope();
  const scope = plantScope.scope;
  const activePlantId = plantScope.activePlantId;
  const queryClient = useQueryClient();
  const isAssignedEmpty = scope?.mode === "EMPTY";
  const canMutate = user?.applicationRole === "SUPER_ADMIN" || user?.applicationRole === "MANAGE";
  const [filters, setFilters] = useState<Filters>({ plantId: activePlantId ?? undefined });
  const plants = useListPlants({ query: { enabled: Boolean(scope) && !isAssignedEmpty } });
  const plantItems = plants.data?.data.items ?? [];
  const availablePlants = useMemo(() => permittedPlants(plantItems, scope), [plantItems, scope]);
  const machinePlantId = filters.plantId || activePlantId || availablePlants[0]?.id || "";
  const machines = useListMachines(
    { plantId: machinePlantId || undefined },
    {
      query: {
        enabled: Boolean(machinePlantId) && !isAssignedEmpty,
        queryKey: ["machines", "installations", machinePlantId],
      },
    },
  );
  const spareparts = useListSpareparts();
  const machineItems = machines.data?.data.items ?? [];
  const sparepartItems = spareparts.data?.data.items ?? [];
  const installationParams = {
    plantId: filters.plantId,
    machineId: machineItems.some((machine) => machine.id === filters.machineId) ? filters.machineId : undefined,
    sparepartId: filters.sparepartId,
    limit: 100,
  };
  const installations = useListMachineSparepartInstallations(installationParams, {
    query: {
      enabled: Boolean(scope) && !isAssignedEmpty && availablePlants.length > 0,
      queryKey: [
        "machine-sparepart-installations",
        activePlantId,
        filters.plantId ?? ALL,
        installationParams.machineId,
        filters.sparepartId,
      ],
    },
  });
  const createInstallation = useCreateMachineSparepartInstallation({
    mutation: { onSuccess: invalidateInstallationData },
  });
  const updateInstallation = useUpdateMachineSparepartInstallation({
    mutation: { onSuccess: invalidateInstallationData },
  });
  const deleteInstallation = useDeleteMachineSparepartInstallation({
    mutation: { onSuccess: invalidateInstallationData },
  });
  const [dialogMode, setDialogMode] = useState<DialogMode | null>(null);
  const [form, setForm] = useState<InstallationForm>(EMPTY_FORM);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<InstallationView | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const installationItems = installations.data?.data.items ?? [];
  const isLoading = plants.isLoading || machines.isLoading || spareparts.isLoading || installations.isLoading;
  const isSaving = createInstallation.isPending || updateInstallation.isPending;
  const loadError = [plants.error, machines.error, spareparts.error, installations.error]
    .map(errorResponse)
    .find((error) => error?.code === "FORBIDDEN");

  useEffect(() => {
    if (!filters.plantId && activePlantId) {
      setFilters((current) => ({ ...current, plantId: activePlantId }));
    }
  }, [activePlantId, filters.plantId]);

  function invalidateInstallationData() {
    queryClient.invalidateQueries({ queryKey: ["machine-sparepart-installations", activePlantId] });
    queryClient.invalidateQueries({ queryKey: getListMachineSparepartInstallationsQueryKey(installationParams) });
    queryClient.invalidateQueries({ queryKey: getListMachinesQueryKey({ plantId: machinePlantId || undefined }) });
    queryClient.invalidateQueries({ queryKey: getListSparepartsQueryKey() });
  }

  function openCreateDialog() {
    setDialogMode({ type: "create" });
    setForm({ ...EMPTY_FORM, machineId: machineItems[0]?.id ?? "", sparepartId: sparepartItems[0]?.id ?? "" });
    setFieldErrors({});
    setFormError(null);
  }

  function openEditDialog(installation: InstallationView) {
    setDialogMode({ type: "edit", installation });
    setForm({
      machineId: installation.machineId ?? "",
      sparepartId: installation.sparepartId ?? "",
      expectedProductionCount: String(installation.expectedProductionCount ?? ""),
      baselineCounter: String(installation.baselineCounter ?? "0"),
      thresholdPercentage: String(installation.thresholdPercentage ?? "90"),
    });
    setFieldErrors({});
    setFormError(null);
  }

  async function submitInstallation(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFieldErrors({});
    setFormError(null);

    try {
      const parsed = parseLifetimeFields(form);
      if (!parsed.ok) {
        setFieldErrors(parsed.fieldErrors);
        return;
      }
      if (dialogMode?.type === "edit") {
        const payload = updatePayload(form, parsed.values);
        await updateInstallation.mutateAsync({ installationId: dialogMode.installation.id ?? "", data: payload });
        toast.success("Installation updated.");
      } else {
        const payload = createPayload(form, parsed.values);
        await createInstallation.mutateAsync({ data: payload });
        toast.success("Installation created.");
      }
      setDialogMode(null);
    } catch (error) {
      const response = errorResponse(error);
      setFieldErrors(response?.fieldErrors ?? {});
      setFormError(response?.message ?? "Installation request failed.");
      toast.error(response?.message ?? "Installation request failed.");
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) {
      return;
    }
    setDeleteError(null);

    try {
      await deleteInstallation.mutateAsync({ installationId: deleteTarget.id ?? "" });
      toast.success("Installation deleted.");
      setDeleteTarget(null);
    } catch (error) {
      const message = errorResponse(error)?.message ?? "Installation delete failed.";
      setDeleteError(message);
      toast.error(message);
    }
  }

  const canCreate = canMutate && machineItems.length > 0 && sparepartItems.length > 0 && !isAssignedEmpty;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Machine sparepart installations</CardTitle>
        <CardDescription>Capture lifetime baseline counters for installed spareparts on machines.</CardDescription>
        <CardAction>
          <div className="flex flex-wrap items-center gap-2">
            {!canMutate ? <Badge variant="secondary">Read-only</Badge> : null}
            {canMutate ? (
              <Button onClick={openCreateDialog} disabled={!canCreate || isLoading}>
                Create installation
              </Button>
            ) : null}
          </div>
        </CardAction>
      </CardHeader>
      <CardContent className="space-y-4">
        {isAssignedEmpty ? (
          <InstallationState title="No plant assignment" description="Your account has no assigned plant scope." />
        ) : null}
        {isLoading ? <InstallationSkeleton /> : null}
        {plants.isError || machines.isError || spareparts.isError || installations.isError ? (
          <InstallationState
            title={loadError ? "Installations access is forbidden" : "Installations could not be loaded"}
            description={loadError?.message ?? "Refresh data or contact administrator if access should be available."}
            action={
              <Button
                variant="outline"
                onClick={() =>
                  void Promise.all([
                    plants.refetch(),
                    machines.refetch(),
                    spareparts.refetch(),
                    installations.refetch(),
                  ])
                }
              >
                Retry
              </Button>
            }
          />
        ) : null}
        {!isLoading && !isAssignedEmpty && availablePlants.length === 0 ? (
          <InstallationState
            title="No plants available"
            description="Create or assign a plant before installing spareparts."
          />
        ) : null}
        {!isLoading && !isAssignedEmpty && availablePlants.length > 0 && machineItems.length === 0 ? (
          <InstallationState
            title="No machines available"
            description="Create a machine before installing spareparts."
          />
        ) : null}
        {!isLoading && !spareparts.isError && sparepartItems.length === 0 ? (
          <InstallationState
            title="No spareparts available"
            description="Create spareparts before installing them on machines."
          />
        ) : null}
        {!isLoading && availablePlants.length > 0 && machineItems.length > 0 && sparepartItems.length > 0 ? (
          <InstallationFilters
            plants={availablePlants}
            machines={machineItems}
            spareparts={sparepartItems}
            filters={filters}
            onChange={setFilters}
          />
        ) : null}
        {!isLoading &&
        !installations.isError &&
        installationItems.length === 0 &&
        machineItems.length > 0 &&
        sparepartItems.length > 0 ? (
          <InstallationState
            title="No installations yet"
            description="Create the first baseline installation or adjust filters."
          />
        ) : null}
        {!isLoading && !installations.isError && installationItems.length > 0 ? (
          <InstallationTable
            items={installationItems}
            canMutate={canMutate}
            onEdit={openEditDialog}
            onDelete={(installation) => {
              setDeleteError(null);
              setDeleteTarget(installation);
            }}
          />
        ) : null}
      </CardContent>

      <Dialog open={dialogMode !== null} onOpenChange={(open) => !open && setDialogMode(null)}>
        <DialogContent className="max-w-2xl">
          <form onSubmit={submitInstallation} className="space-y-4">
            <DialogHeader>
              <DialogTitle>{dialogMode?.type === "edit" ? "Edit installation" : "Create installation"}</DialogTitle>
              <DialogDescription>
                Lifetime consumption is counter-based from current counter minus baseline counter.
              </DialogDescription>
            </DialogHeader>
            {formError ? (
              <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">{formError}</p>
            ) : null}
            <div className="grid gap-4 sm:grid-cols-2">
              <MachineSelect
                value={form.machineId}
                error={fieldErrors.machineId}
                disabled={isSaving || dialogMode?.type === "edit"}
                items={machineItems}
                onChange={(machineId) => setForm((current) => ({ ...current, machineId }))}
              />
              <SparepartSelect
                value={form.sparepartId}
                error={fieldErrors.sparepartId}
                disabled={isSaving || dialogMode?.type === "edit"}
                items={sparepartItems}
                onChange={(sparepartId) => setForm((current) => ({ ...current, sparepartId }))}
              />
              <NumberField
                id="installation-expected-count"
                label="Expected production count"
                value={form.expectedProductionCount}
                error={fieldErrors.expectedProductionCount}
                disabled={isSaving}
                min={1}
                onChange={(expectedProductionCount) => setForm((current) => ({ ...current, expectedProductionCount }))}
              />
              <NumberField
                id="installation-baseline-counter"
                label="Baseline counter"
                value={form.baselineCounter}
                error={fieldErrors.baselineCounter}
                disabled={isSaving}
                min={0}
                onChange={(baselineCounter) => setForm((current) => ({ ...current, baselineCounter }))}
              />
              <NumberField
                id="installation-threshold"
                label="Threshold percentage"
                value={form.thresholdPercentage}
                error={fieldErrors.thresholdPercentage}
                disabled={isSaving}
                min={1}
                max={100}
                onChange={(thresholdPercentage) => setForm((current) => ({ ...current, thresholdPercentage }))}
              />
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setDialogMode(null)} disabled={isSaving}>
                Cancel
              </Button>
              <Button type="submit" disabled={isSaving || !form.machineId || !form.sparepartId}>
                {isSaving ? <Loader2Icon className="animate-spin" /> : null}
                Save installation
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <AlertDialog open={deleteTarget !== null} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete installation?</AlertDialogTitle>
            <AlertDialogDescription>
              This removes the baseline for {deleteTarget?.machineCode} · {deleteTarget?.sparepartCode}. Future alert or
              audit records may block deletion.
            </AlertDialogDescription>
            {deleteError ? (
              <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">{deleteError}</p>
            ) : null}
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleteInstallation.isPending}>Cancel</AlertDialogCancel>
            <Button variant="destructive" onClick={confirmDelete} disabled={deleteInstallation.isPending}>
              {deleteInstallation.isPending ? <Loader2Icon className="animate-spin" /> : null}
              Delete installation
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  );
}

function InstallationFilters({
  plants,
  machines,
  spareparts,
  filters,
  onChange,
}: {
  plants: PlantView[];
  machines: MachineView[];
  spareparts: SparepartView[];
  filters: Filters;
  onChange: (filters: Filters) => void;
}) {
  return (
    <div className="grid gap-3 rounded-lg border p-3 md:grid-cols-3">
      <Select
        value={filters.plantId ?? ALL}
        onValueChange={(plantId) =>
          onChange({
            plantId: plantId === ALL ? undefined : plantId,
            machineId: undefined,
            sparepartId: filters.sparepartId,
          })
        }
      >
        <SelectTrigger>
          <SelectValue placeholder="Plant" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={ALL}>All plants</SelectItem>
          {plants
            .filter((plant) => plant.id)
            .map((plant) => (
              <SelectItem key={plant.id} value={plant.id ?? ""}>
                {plant.code} · {plant.name}
              </SelectItem>
            ))}
        </SelectContent>
      </Select>
      <Select
        value={filters.machineId ?? ALL}
        onValueChange={(machineId) => onChange({ ...filters, machineId: machineId === ALL ? undefined : machineId })}
      >
        <SelectTrigger>
          <SelectValue placeholder="Machine" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={ALL}>All machines</SelectItem>
          {machines
            .filter((machine) => machine.id)
            .map((machine) => (
              <SelectItem key={machine.id} value={machine.id ?? ""}>
                {machine.code} · {machine.name || "Unnamed"}
              </SelectItem>
            ))}
        </SelectContent>
      </Select>
      <Select
        value={filters.sparepartId ?? ALL}
        onValueChange={(sparepartId) =>
          onChange({ ...filters, sparepartId: sparepartId === ALL ? undefined : sparepartId })
        }
      >
        <SelectTrigger>
          <SelectValue placeholder="Sparepart" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={ALL}>All spareparts</SelectItem>
          {spareparts
            .filter((sparepart) => sparepart.id)
            .map((sparepart) => (
              <SelectItem key={sparepart.id} value={sparepart.id ?? ""}>
                {sparepart.code} · {sparepart.name}
              </SelectItem>
            ))}
        </SelectContent>
      </Select>
    </div>
  );
}

function InstallationTable({
  items,
  canMutate,
  onEdit,
  onDelete,
}: {
  items: InstallationView[];
  canMutate: boolean;
  onEdit: (installation: InstallationView) => void;
  onDelete: (installation: InstallationView) => void;
}) {
  return (
    <div className="overflow-x-auto rounded-lg border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Plant</TableHead>
            <TableHead>Group</TableHead>
            <TableHead>Machine</TableHead>
            <TableHead>Sparepart</TableHead>
            <TableHead>Expected</TableHead>
            <TableHead>Baseline</TableHead>
            <TableHead>Current</TableHead>
            <TableHead>Consumed</TableHead>
            <TableHead>Threshold</TableHead>
            <TableHead>Basis</TableHead>
            <TableHead>Created</TableHead>
            <TableHead>Updated</TableHead>
            <TableHead className="text-right">Actions</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {items.map((installation) => (
            <TableRow key={installation.id ?? `${installation.machineId}-${installation.sparepartId}`}>
              <TableCell>
                {installation.plantCode} · {installation.plantName}
              </TableCell>
              <TableCell>{installation.machineGroupName ?? "-"}</TableCell>
              <TableCell>
                <div className="font-medium">{installation.machineCode}</div>
                <div className="text-muted-foreground text-xs">{installation.machineName || "Unnamed"}</div>
              </TableCell>
              <TableCell>
                <div className="font-medium">{installation.sparepartCode}</div>
                <div className="text-muted-foreground text-xs">{installation.sparepartName}</div>
                <div className="text-muted-foreground text-xs">
                  {[
                    installation.category?.name,
                    installation.brand?.name,
                    installation.kind?.name,
                    installation.type?.name,
                  ]
                    .filter(Boolean)
                    .join(" / ")}
                </div>
              </TableCell>
              <TableCell>{formatNumber(installation.expectedProductionCount)}</TableCell>
              <TableCell>{formatNumber(installation.baselineCounter)}</TableCell>
              <TableCell>{formatNullableNumber(installation.currentCount)}</TableCell>
              <TableCell>{formatConsumed(installation)}</TableCell>
              <TableCell>{installation.thresholdPercentage ?? "-"}%</TableCell>
              <TableCell>
                <Badge variant="secondary">{installation.calculationBasis ?? "COUNTER_BASED"}</Badge>
              </TableCell>
              <TableCell>{installation.createdAt ? formatDateTime(installation.createdAt) : "-"}</TableCell>
              <TableCell>{installation.updatedAt ? formatDateTime(installation.updatedAt) : "-"}</TableCell>
              <TableCell className="text-right">
                {canMutate ? (
                  <div className="flex justify-end gap-2">
                    <Button variant="outline" size="sm" onClick={() => onEdit(installation)}>
                      Edit
                    </Button>
                    <Button variant="destructive" size="sm" onClick={() => onDelete(installation)}>
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
    </div>
  );
}

function MachineSelect({
  value,
  error,
  disabled,
  items,
  onChange,
}: {
  value: string;
  error?: string;
  disabled?: boolean;
  items: MachineView[];
  onChange: (value: string) => void;
}) {
  return (
    <div className="grid gap-2">
      <Label>Machine</Label>
      <Select value={value} onValueChange={onChange} disabled={disabled ?? items.length === 0}>
        <SelectTrigger aria-invalid={Boolean(error)}>
          <SelectValue placeholder="Select machine" />
        </SelectTrigger>
        <SelectContent>
          {items.map((machine) => (
            <SelectItem key={machine.id ?? machine.code} value={machine.id ?? ""}>
              {machine.code} · {machine.name || "Unnamed"}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      {error ? <p className="text-destructive text-sm">{error}</p> : null}
    </div>
  );
}

function SparepartSelect({
  value,
  error,
  disabled,
  items,
  onChange,
}: {
  value: string;
  error?: string;
  disabled?: boolean;
  items: SparepartView[];
  onChange: (value: string) => void;
}) {
  return (
    <div className="grid gap-2">
      <Label>Sparepart</Label>
      <Select value={value} onValueChange={onChange} disabled={disabled ?? items.length === 0}>
        <SelectTrigger aria-invalid={Boolean(error)}>
          <SelectValue placeholder="Select sparepart" />
        </SelectTrigger>
        <SelectContent>
          {items.map((sparepart) => (
            <SelectItem key={sparepart.id ?? sparepart.code} value={sparepart.id ?? ""}>
              {sparepart.code} · {sparepart.name}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      {error ? <p className="text-destructive text-sm">{error}</p> : null}
    </div>
  );
}

function NumberField({
  id,
  label,
  value,
  error,
  disabled,
  min,
  max,
  onChange,
}: {
  id: string;
  label: string;
  value: string;
  error?: string;
  disabled?: boolean;
  min: number;
  max?: number;
  onChange: (value: string) => void;
}) {
  return (
    <div className="grid gap-2">
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        type="number"
        min={min}
        max={max}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        aria-invalid={Boolean(error)}
        disabled={disabled}
      />
      {error ? <p className="text-destructive text-sm">{error}</p> : null}
    </div>
  );
}

function InstallationState({
  title,
  description,
  action,
}: {
  title: string;
  description: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 rounded-lg border border-dashed p-6 text-center">
      <TriangleAlertIcon className="size-8 text-muted-foreground" />
      <div>
        <h2 className="font-medium">{title}</h2>
        <p className="text-muted-foreground text-sm">{description}</p>
      </div>
      {action}
    </div>
  );
}

function InstallationSkeleton() {
  return (
    <div className="space-y-3">
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-12 w-full" />
      <Skeleton className="h-12 w-full" />
      <Skeleton className="h-12 w-full" />
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

type ParsedLifetimeFields = {
  expectedProductionCount: number;
  baselineCounter: number;
  thresholdPercentage: number;
};

type ParseResult = { ok: true; values: ParsedLifetimeFields } | { ok: false; fieldErrors: Record<string, string> };

function createPayload(form: InstallationForm, values: ParsedLifetimeFields): InstallationRequest {
  return {
    machineId: form.machineId,
    sparepartId: form.sparepartId,
    ...values,
  };
}

function updatePayload(_form: InstallationForm, values: ParsedLifetimeFields): InstallationUpdateRequest {
  return values;
}

function parseLifetimeFields(form: InstallationForm): ParseResult {
  const fieldErrors: Record<string, string> = {};
  const expectedProductionCount = parseRequiredNumber(form.expectedProductionCount);
  const baselineCounter = parseRequiredNumber(form.baselineCounter);
  const thresholdPercentage = parseRequiredNumber(form.thresholdPercentage);

  if (expectedProductionCount === null || expectedProductionCount < 1) {
    fieldErrors.expectedProductionCount = "Expected production count is required and must be positive.";
  }
  if (baselineCounter === null || baselineCounter < 0) {
    fieldErrors.baselineCounter = "Baseline counter is required and cannot be negative.";
  }
  if (thresholdPercentage === null || thresholdPercentage < 1 || thresholdPercentage > 100) {
    fieldErrors.thresholdPercentage = "Threshold percentage is required and must be between 1 and 100.";
  }

  if (Object.keys(fieldErrors).length > 0) {
    return { ok: false, fieldErrors };
  }

  return { ok: true, values: { expectedProductionCount, baselineCounter, thresholdPercentage } };
}

function parseRequiredNumber(value: string) {
  if (!value.trim()) {
    return null;
  }
  return Number(value);
}

function errorResponse(error: unknown): ErrorResponse | null {
  if (!(error instanceof SyncroApiError) || !error.payload || typeof error.payload !== "object") {
    return null;
  }
  const payload = error.payload as ErrorResponse;
  return typeof payload.code === "string" && typeof payload.message === "string" ? payload : null;
}

function formatNumber(value: number | undefined) {
  return typeof value === "number" ? new Intl.NumberFormat("en").format(value) : "-";
}

function formatNullableNumber(value: number | undefined) {
  return typeof value === "number" ? formatNumber(value) : "Not available";
}

function formatConsumed(installation: InstallationView) {
  if (typeof installation.consumedProductionCount !== "number" || typeof installation.consumedPercentage !== "number") {
    return "Not available";
  }
  return `${formatNumber(installation.consumedProductionCount)} (${installation.consumedPercentage.toFixed(2)}%)`;
}

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}
