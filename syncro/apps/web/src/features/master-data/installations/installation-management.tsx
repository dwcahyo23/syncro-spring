"use client";

import React, { type FormEvent, useEffect, useMemo, useState } from "react";

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
import { CreatableSelect, type CreatableSelectOption } from "@/components/ui/creatable-select";
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
  InstallationRequest,
  InstallationUpdateRequest,
  InstallationView,
  MachineView,
  PlantView,
  SparepartRequest,
  SparepartTaxonomyView,
  SparepartView,
} from "@/lib/api/generated/model";
import { SparepartTaxonomyRequestDimension } from "@/lib/api/generated/model";
import {
  getListMachineSparepartInstallationsQueryKey,
  getListMachinesQueryKey,
  getListSparepartsQueryKey,
  getListSparepartTaxonomiesQueryKey,
  useCreateMachineSparepartInstallation,
  useCreateSparepart,
  useCreateSparepartTaxonomy,
  useDeleteMachineSparepartInstallation,
  useListMachineSparepartInstallations,
  useListMachines,
  useListPlants,
  useListSpareparts,
  useListSparepartTaxonomies,
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
  functionName: string;
  expectedProductionCount: string;
  baselineCounter: string;
  thresholdPercentage: string;
};
type TaxonomyDimension = SparepartTaxonomyView["dimension"];
type SparepartFormState = Omit<SparepartRequest, "code" | "name">;

const ALL = "__all__";
const ALL_PLANTS = "all";
const EMPTY_FORM: InstallationForm = {
  machineId: "",
  sparepartId: "",
  functionName: "Primary",
  expectedProductionCount: "",
  baselineCounter: "0",
  thresholdPercentage: "90",
};
const EMPTY_SPAREPART_FORM: SparepartFormState = {
  machineId: "",
  categoryId: "",
  brandId: "",
  kindId: "",
  typeId: "",
};

export function InstallationManagement() {
  const user = useAuthUser();
  const plantScope = usePlantScope();
  const scope = plantScope.scope;
  const activePlantId = plantScope.activePlantId;
  const queryClient = useQueryClient();
  const isAssignedEmpty = scope?.mode === "EMPTY";
  const canMutate = user?.applicationRole === "SUPER_ADMIN" || user?.applicationRole === "MANAGER_MAINTENANCE";
  const [filters, setFilters] = useState<Filters>({ plantId: normalizePlantId(activePlantId) });
  const [machineSearch, setMachineSearch] = useState("");
  const [dialogMode, setDialogMode] = useState<DialogMode | null>(null);
  const [step, setStep] = useState<1 | 2>(1);
  const [form, setForm] = useState<InstallationForm>(EMPTY_FORM);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [sort, setSort] = useState("machineCode,asc");
  const [inlineSparepartOpen, setInlineSparepartOpen] = useState(false);
  const [inlineSparepartForm, setInlineSparepartForm] = useState<SparepartFormState>(EMPTY_SPAREPART_FORM);
  const [inlineSparepartErrors, setInlineSparepartErrors] = useState<Record<string, string>>({});
  const [inlineSparepartError, setInlineSparepartError] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<InstallationView | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const plants = useListPlants({ query: { enabled: Boolean(scope) && !isAssignedEmpty } });
  const taxonomy = useListSparepartTaxonomies();
  const plantItems = plants.data?.data.items ?? [];
  const availablePlants = useMemo(() => permittedPlants(plantItems, scope), [plantItems, scope]);
  const selectedPlantId = normalizePlantId(activePlantId);
  const filterPlantId = normalizePlantId(filters.plantId);
  const machinePlantId = filterPlantId ?? selectedPlantId ?? availablePlants[0]?.id ?? "";
  const machineLookupParams = {
    plantId: machinePlantId || undefined,
    search: machineSearch.trim() || undefined,
    limit: 25,
  };
  const machines = useListMachines(machineLookupParams, {
    query: {
      enabled: Boolean(machinePlantId) && !isAssignedEmpty,
      queryKey: ["machines", "installations", machinePlantId, machineLookupParams.search],
    },
  });
  const selectedInstallationMachineId = form.machineId || undefined;
  const filterSpareparts = useListSpareparts(
    { machineId: filters.machineId, pageable: { page: 0, size: 100, sort: ["code,asc"] } },
    { query: { enabled: !isAssignedEmpty } },
  );
  const formSpareparts = useListSpareparts(
    { machineId: selectedInstallationMachineId, pageable: { page: 0, size: 100, sort: ["code,asc"] } },
    { query: { enabled: Boolean(selectedInstallationMachineId) && !isAssignedEmpty } },
  );
  const machineItems = machines.data?.data.items ?? [];
  const filterSparepartItems = filterSpareparts.data?.data.items ?? [];
  const formSparepartItems = formSpareparts.data?.data.items ?? [];
  const installationParams = {
    plantId: filterPlantId,
    machineId: machineItems.some((machine) => machine.id === filters.machineId) ? filters.machineId : undefined,
    sparepartId: filters.sparepartId,
    pageable: { page, size, sort: [sort] },
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
        page,
        size,
        sort,
      ],
    },
  });
  const createInstallation = useCreateMachineSparepartInstallation({
    mutation: { onSuccess: invalidateInstallationData },
  });
  const createSparepart = useCreateSparepart({
    mutation: { onSuccess: invalidateInstallationData },
  });
  const createTaxonomy = useCreateSparepartTaxonomy({
    mutation: { onSuccess: invalidateTaxonomyData },
  });
  const updateInstallation = useUpdateMachineSparepartInstallation({
    mutation: { onSuccess: invalidateInstallationData },
  });
  const deleteInstallation = useDeleteMachineSparepartInstallation({
    mutation: { onSuccess: invalidateInstallationData },
  });
  const installationItems = installations.data?.data.items ?? [];
  const taxonomyItems = taxonomy.data?.data.items ?? [];
  const taxonomyByDimension = useMemo(() => groupByDimension(taxonomyItems), [taxonomyItems]);
  const isLoading =
    plants.isLoading ||
    machines.isLoading ||
    filterSpareparts.isLoading ||
    (Boolean(selectedInstallationMachineId) && formSpareparts.isLoading) ||
    taxonomy.isLoading ||
    installations.isLoading;
  const isSaving = createInstallation.isPending || updateInstallation.isPending;
  const loadError = [plants.error, machines.error, filterSpareparts.error, taxonomy.error, installations.error]
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
    queryClient.invalidateQueries({ queryKey: getListMachinesQueryKey(machineLookupParams) });
    queryClient.invalidateQueries({ queryKey: getListSparepartsQueryKey() });
  }

  function invalidateTaxonomyData() {
    queryClient.invalidateQueries({ queryKey: getListSparepartTaxonomiesQueryKey() });
  }

  // createTaxonomyValue removed as taxonomy creation is now queued in submitInlineSparepart

  function openCreateDialog() {
    setDialogMode({ type: "create" });
    setStep(1);
    setForm(EMPTY_FORM);
    setFieldErrors({});
    setFormError(null);
    resetInlineSparepartForm("");
  }

  function openEditDialog(installation: InstallationView) {
    setDialogMode({ type: "edit", installation });
    setStep(1);
    setForm({
      machineId: installation.machineId ?? "",
      sparepartId: installation.sparepartId ?? "",
      functionName: installation.functionName ?? "Primary",
      expectedProductionCount: String(installation.expectedProductionCount ?? ""),
      baselineCounter: String(installation.baselineCounter ?? "0"),
      thresholdPercentage: String(installation.thresholdPercentage ?? "90"),
    });
    setFieldErrors({});
    setFormError(null);
    resetInlineSparepartForm();
  }

  function resetInlineSparepartForm(machineId = form.machineId) {
    setInlineSparepartOpen(false);
    setInlineSparepartForm(defaultSparepartForm(taxonomyByDimension, machineId));
    setInlineSparepartErrors({});
    setInlineSparepartError(null);
  }

  async function submitInlineSparepart() {
    setInlineSparepartErrors({});
    setInlineSparepartError(null);

    try {
      const payload = { ...inlineSparepartForm };

      if (payload.categoryId.startsWith("pending-")) {
        const name = payload.categoryId.replace("pending-", "");
        const res = await createTaxonomy.mutateAsync({
          data: { dimension: "CATEGORY", name, code: taxonomyCode(name) },
        });
        payload.categoryId = res.data.id ?? "";
      }
      if (payload.kindId.startsWith("pending-")) {
        const name = payload.kindId.replace("pending-", "");
        const res = await createTaxonomy.mutateAsync({
          data: { dimension: "KIND", name, code: taxonomyCode(name), categoryId: payload.categoryId },
        });
        payload.kindId = res.data.id ?? "";
      }
      if (payload.brandId.startsWith("pending-")) {
        const name = payload.brandId.replace("pending-", "");
        const res = await createTaxonomy.mutateAsync({
          data: { dimension: "BRAND", name, code: taxonomyCode(name), categoryId: payload.categoryId },
        });
        payload.brandId = res.data.id ?? "";
      }
      if (payload.typeId.startsWith("pending-")) {
        const name = payload.typeId.replace("pending-", "");
        const res = await createTaxonomy.mutateAsync({
          data: { dimension: "TYPE", name, code: taxonomyCode(name), categoryId: payload.categoryId },
        });
        payload.typeId = res.data.id ?? "";
      }

      await queryClient.invalidateQueries({ queryKey: getListSparepartTaxonomiesQueryKey() });

      const response = await createSparepart.mutateAsync({ data: payload as SparepartRequest });
      const sparepart = response.data;
      if (sparepart.id) {
        setForm((current) => ({ ...current, sparepartId: sparepart.id ?? "" }));
      }
      await queryClient.invalidateQueries({ queryKey: getListSparepartsQueryKey() });
      setInlineSparepartOpen(false);
      toast.success("Sparepart created and selected.");
    } catch (error) {
      const response = errorResponse(error);
      setInlineSparepartErrors(response?.fieldErrors ?? {});
      setInlineSparepartError(response?.message ?? "Sparepart request failed.");
      toast.error(response?.message ?? "Sparepart request failed.");
    }
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

  const canCreate = canMutate && machineItems.length > 0 && filterSparepartItems.length > 0 && !isAssignedEmpty;

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
        {plants.isError || machines.isError || filterSpareparts.isError || taxonomy.isError || installations.isError ? (
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
                    filterSpareparts.refetch(),
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
        {!isLoading && availablePlants.length > 0 ? (
          <InstallationFilters
            plants={availablePlants}
            machines={machineItems}
            spareparts={filterSparepartItems}
            filters={filters}
            onChange={setFilters}
          />
        ) : null}
        {!isLoading && !isAssignedEmpty && availablePlants.length > 0 && machineItems.length === 0 ? (
          <InstallationState
            title="No machines available"
            description="Create a machine before installing spareparts."
          />
        ) : null}
        {!isLoading && !filterSpareparts.isError && filterSparepartItems.length === 0 ? (
          <InstallationState
            title="No spareparts available"
            description="Create spareparts before installing them on machines."
          />
        ) : null}
        {!isLoading &&
        !installations.isError &&
        installationItems.length === 0 &&
        machineItems.length > 0 &&
        filterSparepartItems.length > 0 ? (
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
            sort={sort}
            onSortChange={setSort}
          />
        ) : null}
        {!isLoading && !installations.isError && installations.data?.data ? (
          <DataTablePagination
            page={page}
            size={size}
            totalElements={installations.data.data.totalElements}
            onPageChange={setPage}
            onSizeChange={(newSize) => {
              setSize(newSize);
              setPage(0);
            }}
          />
        ) : null}
      </CardContent>

      <Dialog open={dialogMode !== null} onOpenChange={(open) => !open && setDialogMode(null)}>
        <DialogContent className="top-4 max-h-[calc(100svh-2rem)] translate-y-0 overflow-y-auto sm:max-w-2xl">
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
            <div className="my-2 flex items-center gap-2 text-sm">
              <div
                className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full ${step >= 1 ? "bg-primary text-primary-foreground" : "bg-muted text-muted-foreground"}`}
              >
                1
              </div>
              <span className={step >= 1 ? "font-medium" : "text-muted-foreground"}>Identity</span>
              <div className="h-px flex-1 bg-border" />
              <div
                className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full ${step >= 2 ? "bg-primary text-primary-foreground" : "bg-muted text-muted-foreground"}`}
              >
                2
              </div>
              <span className={step >= 2 ? "font-medium" : "text-muted-foreground"}>Counters</span>
            </div>
            <div className="grid gap-4 md:grid-cols-2">
              {step === 1 ? (
                <>
                  {inlineSparepartOpen ? (
                    <InlineSparepartForm
                      form={inlineSparepartForm}
                      fieldErrors={inlineSparepartErrors}
                      formError={inlineSparepartError}
                      disabled={createSparepart.isPending || createTaxonomy.isPending}
                      taxonomyByDimension={taxonomyByDimension}
                      taxonomyDisabled={createTaxonomy.isPending}
                      onChange={setInlineSparepartForm}
                      onCancel={resetInlineSparepartForm}
                      onSubmit={submitInlineSparepart}
                    />
                  ) : null}
                  <MachineSelect
                    value={form.machineId}
                    search={machineSearch}
                    error={fieldErrors.machineId}
                    disabled={isSaving || dialogMode?.type === "edit"}
                    items={machineItems}
                    onSearchChange={setMachineSearch}
                    onChange={(machineId) => setForm((current) => ({ ...current, machineId }))}
                  />
                  <SparepartSelect
                    value={form.sparepartId}
                    error={fieldErrors.sparepartId}
                    disabled={isSaving || dialogMode?.type === "edit"}
                    items={formSparepartItems}
                    canCreateInline={dialogMode?.type !== "edit"}
                    onCreateInline={() => {
                      setInlineSparepartForm((current) => ({ ...current, machineId: form.machineId }));
                      setInlineSparepartOpen((open) => !open);
                    }}
                    onChange={(sparepartId) => setForm((current) => ({ ...current, sparepartId }))}
                  />
                  <TextField
                    id="installation-function-name"
                    label="Function / usage"
                    value={form.functionName}
                    error={fieldErrors.functionName}
                    disabled={isSaving}
                    onChange={(functionName) => setForm((current) => ({ ...current, functionName }))}
                  />
                </>
              ) : null}
              {step === 2 ? (
                <>
                  <NumberField
                    id="installation-expected-count"
                    label="Expected production count"
                    value={form.expectedProductionCount}
                    error={fieldErrors.expectedProductionCount}
                    disabled={isSaving}
                    min={1}
                    onChange={(expectedProductionCount) =>
                      setForm((current) => ({ ...current, expectedProductionCount }))
                    }
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
                </>
              ) : null}
            </div>
            <DialogFooter>
              {step === 2 ? (
                <Button type="button" variant="outline" onClick={() => setStep(1)} disabled={isSaving}>
                  Back
                </Button>
              ) : (
                <Button type="button" variant="outline" onClick={() => setDialogMode(null)} disabled={isSaving}>
                  Cancel
                </Button>
              )}
              {step === 1 ? (
                <Button
                  type="button"
                  onClick={(e) => {
                    e.preventDefault();
                    setStep(2);
                  }}
                  disabled={!form.machineId || !form.sparepartId || !form.functionName.trim()}
                >
                  Next
                </Button>
              ) : (
                <Button
                  type="submit"
                  disabled={
                    isSaving || !form.expectedProductionCount || !form.baselineCounter || !form.thresholdPercentage
                  }
                >
                  {isSaving ? <Loader2Icon className="animate-spin" /> : null}
                  Save installation
                </Button>
              )}
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
    <div className="grid gap-3 rounded-lg border p-3 sm:grid-cols-[repeat(auto-fill,minmax(14rem,14rem))] sm:justify-start">
      <SearchableSelect
        label="Plant"
        value={filters.plantId}
        options={plants.filter((p) => p.id).map((p) => ({ id: p.id ?? "", label: `${p.code} · ${p.name}` }))}
        onValueChange={(plantId) =>
          onChange({
            plantId: plantId === ALL ? undefined : plantId,
            machineId: undefined,
            sparepartId: filters.sparepartId,
          })
        }
      />
      <SearchableSelect
        label="Machine"
        value={filters.machineId}
        disabled={machines.length === 0}
        options={machines
          .filter((m) => m.id)
          .map((m) => ({ id: m.id ?? "", label: `${m.code} · ${m.name || "Unnamed"} · ${m.plantCode}` }))}
        onValueChange={(machineId) => onChange({ ...filters, machineId: machineId === ALL ? undefined : machineId })}
      />
      <SearchableSelect
        label="Sparepart"
        value={filters.sparepartId}
        disabled={spareparts.length === 0}
        options={spareparts
          .filter((s) => s.id)
          .map((s) => ({ id: s.id ?? "", label: `${s.code} · ${sparepartIdentity(s)}` }))}
        onValueChange={(sparepartId) =>
          onChange({ ...filters, sparepartId: sparepartId === ALL ? undefined : sparepartId })
        }
      />
    </div>
  );
}

function InstallationTable({
  items,
  canMutate,
  onEdit,
  onDelete,
  sort,
  onSortChange,
}: {
  items: InstallationView[];
  canMutate: boolean;
  onEdit: (installation: InstallationView) => void;
  onDelete: (installation: InstallationView) => void;
  sort: string;
  onSortChange: (sort: string) => void;
}) {
  return (
    <div className="overflow-x-auto rounded-lg border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Plant</TableHead>
            <TableHead>Group</TableHead>
            <TableHead>
              <DataTableSortHeader title="Machine" field="machineCode" sort={sort} onSortChange={onSortChange} />
            </TableHead>
            <TableHead>
              <DataTableSortHeader title="Sparepart" field="sparepartCode" sort={sort} onSortChange={onSortChange} />
            </TableHead>
            <TableHead>Function</TableHead>
            <TableHead>Expected</TableHead>
            <TableHead>Baseline</TableHead>
            <TableHead>Current</TableHead>
            <TableHead>Consumed</TableHead>
            <TableHead>Threshold</TableHead>
            <TableHead>Basis</TableHead>
            <TableHead>
              <DataTableSortHeader title="Created" field="createdAt" sort={sort} onSortChange={onSortChange} />
            </TableHead>
            <TableHead>
              <DataTableSortHeader title="Updated" field="updatedAt" sort={sort} onSortChange={onSortChange} />
            </TableHead>
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
                <div className="text-muted-foreground text-xs">{installationSparepartIdentity(installation)}</div>
              </TableCell>
              <TableCell>{installation.functionName ?? "-"}</TableCell>
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
  search,
  error,
  disabled,
  items,
  onSearchChange,
  onChange,
}: {
  value: string;
  search: string;
  error?: string;
  disabled?: boolean;
  items: MachineView[];
  onSearchChange: (value: string) => void;
  onChange: (value: string) => void;
}) {
  return (
    <div className="grid gap-2">
      <Label>Machine</Label>
      <Select value={value} onValueChange={onChange} disabled={disabled ?? false}>
        <SelectTrigger className="w-full min-w-0" aria-invalid={Boolean(error)}>
          <SelectValue placeholder="Select machine" />
        </SelectTrigger>
        <SelectContent>
          <div className="p-2">
            <Input
              value={search}
              placeholder="Search machine code, name, or plant"
              onChange={(event) => onSearchChange(event.target.value)}
              onKeyDown={(event) => event.stopPropagation()}
            />
          </div>
          {items.length === 0 ? (
            <div className="px-2 py-1.5 text-muted-foreground text-sm">No machines found</div>
          ) : null}
          {items.map((machine) => (
            <SelectItem key={machine.id ?? machine.code} value={machine.id ?? ""}>
              {machine.code} · {machine.name || "Unnamed"} · {machine.plantCode}
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
  canCreateInline,
  onCreateInline,
  onChange,
}: {
  value: string;
  error?: string;
  disabled?: boolean;
  items: SparepartView[];
  canCreateInline: boolean;
  onCreateInline: () => void;
  onChange: (value: string) => void;
}) {
  return (
    <div className="grid gap-2">
      <div className="flex items-center justify-between gap-2">
        <Label>Sparepart</Label>
        {canCreateInline ? (
          <Button type="button" variant="link" className="h-auto p-0" onClick={onCreateInline} disabled={disabled}>
            Create inline
          </Button>
        ) : null}
      </div>
      <Select value={value} onValueChange={onChange} disabled={disabled ?? items.length === 0}>
        <SelectTrigger className="w-full min-w-0" aria-invalid={Boolean(error)}>
          <SelectValue placeholder="Select sparepart" />
        </SelectTrigger>
        <SelectContent>
          {items.map((sparepart) => (
            <SelectItem key={sparepart.id ?? sparepart.code} value={sparepart.id ?? ""}>
              {sparepart.code} · {sparepartIdentity(sparepart)}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      {error ? <p className="text-destructive text-sm">{error}</p> : null}
    </div>
  );
}

function InlineSparepartForm({
  form,
  fieldErrors,
  formError,
  disabled,
  taxonomyByDimension,
  taxonomyDisabled,
  onChange,
  onCancel,
  onSubmit,
}: {
  form: SparepartFormState;
  fieldErrors: Record<string, string>;
  formError: string | null;
  disabled: boolean;
  taxonomyByDimension: Map<TaxonomyDimension, SparepartTaxonomyView[]>;
  taxonomyDisabled: boolean;
  onChange: (form: SparepartFormState) => void;
  onCancel: () => void;
  onSubmit: () => void;
}) {
  return (
    <div className="space-y-3 rounded-lg border bg-muted/30 p-3 md:col-span-2">
      <div>
        <h3 className="font-medium text-sm">Create sparepart inline</h3>
        <p className="text-muted-foreground text-xs">
          Create the missing sparepart without losing this installation draft.
        </p>
      </div>
      {formError ? <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">{formError}</p> : null}
      <div className="grid gap-3 md:grid-cols-2">
        <GeneratedCodeHint />
        <TaxonomySelect
          label="Category"
          value={form.categoryId}
          error={fieldErrors.categoryId}
          disabled={disabled}
          items={taxonomyByDimension.get(SparepartTaxonomyRequestDimension.CATEGORY) ?? []}
          creatable={false}
          onCreate={undefined}
          onChange={(categoryId) => onChange({ ...form, categoryId, brandId: "", kindId: "", typeId: "" })}
        />
        <TaxonomySelect
          label="Kind"
          value={form.kindId}
          error={fieldErrors.kindId}
          disabled={disabled}
          items={linkedTaxonomyOptions(taxonomyByDimension, SparepartTaxonomyRequestDimension.KIND, form.categoryId)}
          creatable={!taxonomyDisabled && Boolean(form.categoryId)}
          onCreate={(name) => Promise.resolve(onChange({ ...form, kindId: `pending-${name}` }))}
          onChange={(kindId) => onChange({ ...form, kindId })}
        />
        <TaxonomySelect
          label="Brand"
          value={form.brandId}
          error={fieldErrors.brandId}
          disabled={disabled}
          items={linkedTaxonomyOptions(taxonomyByDimension, SparepartTaxonomyRequestDimension.BRAND, form.categoryId)}
          creatable={!taxonomyDisabled && Boolean(form.categoryId)}
          onCreate={(name) => Promise.resolve(onChange({ ...form, brandId: `pending-${name}` }))}
          onChange={(brandId) => onChange({ ...form, brandId })}
        />
        <TaxonomySelect
          label="Type"
          value={form.typeId}
          error={fieldErrors.typeId}
          disabled={disabled}
          items={linkedTaxonomyOptions(taxonomyByDimension, SparepartTaxonomyRequestDimension.TYPE, form.categoryId)}
          creatable={!taxonomyDisabled && Boolean(form.categoryId)}
          onCreate={(name) => Promise.resolve(onChange({ ...form, typeId: `pending-${name}` }))}
          onChange={(typeId) => onChange({ ...form, typeId })}
        />
      </div>
      <div className="flex flex-wrap justify-end gap-2">
        <Button type="button" variant="outline" onClick={onCancel} disabled={disabled}>
          Cancel sparepart
        </Button>
        <Button type="button" onClick={onSubmit} disabled={disabled}>
          {disabled ? <Loader2Icon className="animate-spin" /> : null}
          Create and select
        </Button>
      </div>
    </div>
  );
}

function TextField({
  id,
  label,
  value,
  error,
  disabled,
  onChange,
}: {
  id: string;
  label: string;
  value?: string;
  error?: string;
  disabled?: boolean;
  onChange: (value: string) => void;
}) {
  return (
    <div className="grid gap-2">
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        value={value ?? ""}
        onChange={(event) => onChange(event.target.value)}
        aria-invalid={Boolean(error)}
        disabled={disabled}
      />
      {error ? <p className="text-destructive text-sm">{error}</p> : null}
    </div>
  );
}

function GeneratedCodeHint() {
  return (
    <div className="grid gap-2 rounded-md border border-dashed p-3 text-sm md:min-h-[4.5rem]">
      <span className="font-medium">Generated code</span>
      <span className="text-muted-foreground">BOM code is assigned after save.</span>
    </div>
  );
}

function TaxonomySelect({
  label,
  value,
  error,
  disabled,
  items,
  creatable,
  onCreate,
  onChange,
}: {
  label: string;
  value?: string;
  error?: string;
  disabled?: boolean;
  items: SparepartTaxonomyView[];
  creatable?: boolean;
  onCreate?: (name: string) => Promise<void>;
  onChange: (value: string) => void;
}) {
  const options = items.map((item) => ({ value: item.id ?? "", label: `${item.name} (${item.code})` }));
  const selectedOption = options.find((option) => option.value === value) ?? null;

  async function submitCreate(inputValue: string) {
    if (!onCreate || !inputValue.trim()) {
      return;
    }
    await onCreate(inputValue);
  }

  return (
    <div className="grid gap-2">
      <Label>{label}</Label>
      <CreatableSelect
        value={selectedOption}
        options={options}
        placeholder={`Select ${label.toLowerCase()}`}
        invalid={Boolean(error)}
        isDisabled={Boolean(disabled) || (!creatable && items.length === 0)}
        isClearable={false}
        isSearchable
        formatCreateLabel={(inputValue) => `Create ${label.toLowerCase()} “${inputValue.trim()}”`}
        noOptionsMessage={() => `No ${label.toLowerCase()} found`}
        onCreateOption={creatable ? submitCreate : undefined}
        onChange={(option) => onChange((option as CreatableSelectOption | null)?.value ?? "")}
      />
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
        type="text"
        inputMode="numeric"
        pattern="[0-9]*"
        value={value}
        onChange={(event) => {
          const nextValue = event.target.value.replace(/\D/g, "");
          if (nextValue === "" || (Number(nextValue) >= min && (max === undefined || Number(nextValue) <= max))) {
            onChange(nextValue);
          }
        }}
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

function normalizePlantId(plantId: string | undefined) {
  return plantId === ALL || plantId === ALL_PLANTS ? undefined : plantId;
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

function sparepartIdentity(sparepart: SparepartView) {
  return (
    [sparepart.category?.name, sparepart.kind?.name, sparepart.brand?.name, sparepart.type?.name]
      .filter(Boolean)
      .join(" · ") || "Unknown identity"
  );
}

function installationSparepartIdentity(installation: InstallationView) {
  return (
    [installation.category?.name, installation.kind?.name, installation.brand?.name, installation.type?.name]
      .filter(Boolean)
      .join(" · ") || "Unknown identity"
  );
}

function groupByDimension(items: SparepartTaxonomyView[]) {
  return items.reduce((groups, item) => {
    if (item.id && item.dimension) {
      groups.set(item.dimension, [...(groups.get(item.dimension) ?? []), item]);
    }
    return groups;
  }, new Map<TaxonomyDimension, SparepartTaxonomyView[]>());
}

function linkedTaxonomyOptions(
  taxonomyByDimension: Map<TaxonomyDimension, SparepartTaxonomyView[]>,
  dimension: TaxonomyDimension,
  categoryId: string,
) {
  return (taxonomyByDimension.get(dimension) ?? []).filter((item) => !categoryId || item.categoryId === categoryId);
}

function taxonomyCode(name: string) {
  return name
    .trim()
    .toUpperCase()
    .replace(/[^A-Z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "");
}

function defaultSparepartForm(
  taxonomyByDimension: Map<TaxonomyDimension, SparepartTaxonomyView[]>,
  machineId: string,
): SparepartFormState {
  return {
    machineId,
    categoryId: taxonomyByDimension.get(SparepartTaxonomyRequestDimension.CATEGORY)?.[0]?.id ?? "",
    brandId: taxonomyByDimension.get(SparepartTaxonomyRequestDimension.BRAND)?.[0]?.id ?? "",
    kindId: taxonomyByDimension.get(SparepartTaxonomyRequestDimension.KIND)?.[0]?.id ?? "",
    typeId: taxonomyByDimension.get(SparepartTaxonomyRequestDimension.TYPE)?.[0]?.id ?? "",
  };
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
    functionName: form.functionName.trim(),
    ...values,
  };
}

function updatePayload(form: InstallationForm, values: ParsedLifetimeFields): InstallationUpdateRequest {
  return { functionName: form.functionName.trim(), ...values };
}

function parseLifetimeFields(form: InstallationForm): ParseResult {
  const fieldErrors: Record<string, string> = {};
  const expectedProductionCount = parseRequiredNumber(form.expectedProductionCount);
  const baselineCounter = parseRequiredNumber(form.baselineCounter);
  const thresholdPercentage = parseRequiredNumber(form.thresholdPercentage);

  if (!form.functionName.trim()) {
    fieldErrors.functionName = "Function / usage is required.";
  }
  if (expectedProductionCount === null || expectedProductionCount < 1) {
    fieldErrors.expectedProductionCount = "Expected production count is required and must be positive.";
  }
  if (baselineCounter === null || baselineCounter < 0) {
    fieldErrors.baselineCounter = "Baseline counter is required and cannot be negative.";
  }
  if (thresholdPercentage === null || thresholdPercentage < 1 || thresholdPercentage > 100) {
    fieldErrors.thresholdPercentage = "Threshold percentage is required and must be between 1 and 100.";
  }

  if (
    Object.keys(fieldErrors).length > 0 ||
    expectedProductionCount === null ||
    baselineCounter === null ||
    thresholdPercentage === null
  ) {
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

function SearchableSelect({
  label,
  value,
  options,
  onValueChange,
  disabled,
}: {
  label: string;
  value?: string;
  options: { id: string; label: string }[];
  onValueChange: (value: string) => void;
  disabled?: boolean;
}) {
  const [search, setSearch] = React.useState("");
  const filtered = search.trim()
    ? options.filter((o) => o.label.toLowerCase().includes(search.trim().toLowerCase()))
    : options;
  const selected = options.find((o) => o.id === value);

  return (
    <Select
      value={value ?? ALL}
      onValueChange={(v) => {
        onValueChange(v);
        setSearch("");
      }}
      disabled={disabled}
    >
      <SelectTrigger className="w-full min-w-0">
        <SelectValue placeholder={`All ${label}`}>{selected ? selected.label : `All ${label}`}</SelectValue>
      </SelectTrigger>
      <SelectContent>
        <div className="p-2">
          <Input
            placeholder={`Search ${label.toLowerCase()}...`}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            onClick={(e) => e.stopPropagation()}
            onKeyDown={(e) => e.stopPropagation()}
          />
        </div>
        <SelectItem value={ALL}>All {label}</SelectItem>
        {filtered.map((o) => (
          <SelectItem key={o.id} value={o.id}>
            {o.label}
          </SelectItem>
        ))}
        {filtered.length === 0 ? (
          <p className="px-2 py-3 text-center text-sm text-muted-foreground">No results</p>
        ) : null}
      </SelectContent>
    </Select>
  );
}
