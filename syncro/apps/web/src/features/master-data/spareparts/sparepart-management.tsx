"use client";

import React, { type FormEvent, useMemo, useState } from "react";

import { useQueryClient } from "@tanstack/react-query";
import { Loader2Icon, SearchIcon, Trash2, TriangleAlertIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { toast } from "sonner";

import { CurrencyPriceInput, type CurrencyPriceValue } from "@/components/syncro/currency-price-input";
import { LeadTimeInput } from "@/components/syncro/lead-time-input";
import { MaterialCodeField } from "@/components/syncro/material-code-field";
import { PriceHistoryTable } from "@/components/syncro/price-history-table";
import { SparepartImageUpload } from "@/components/syncro/sparepart-image-upload";
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
import { apiErrorMessage, errorResponse } from "@/lib/api/error-response";
import type {
  MachineView,
  SparepartPriceEntryView,
  SparepartProcurementRequest,
  SparepartRequest,
  SparepartTaxonomyView,
  SparepartView,
} from "@/lib/api/generated/model";
import { SparepartTaxonomyRequestDimension } from "@/lib/api/generated/model";
import {
  getGetSparepartImageQueryKey,
  getListSparepartPriceEntriesQueryKey,
  getListSparepartsQueryKey,
  getListSparepartTaxonomiesQueryKey,
  useCreateSparepart,
  useCreateSparepartImage,
  useCreateSparepartPriceEntries,
  useCreateSparepartTaxonomy,
  useDeleteSparepart,
  useDeleteSparepartImage,
  useGetSparepartImage,
  useListMachines,
  useListSparepartPriceEntries,
  useListSpareparts,
  useListSparepartTaxonomies,
  usePatchSparepartProcurement,
  useUpdateSparepart,
} from "@/lib/api/generated/syncro";
import { useAuthUser } from "@/lib/auth/use-auth-user";
import { useDateTimeFormatter } from "@/lib/i18n/format";

type DialogMode = { type: "create"; sparepart?: never } | { type: "edit"; sparepart: SparepartView };
type TaxonomyDimension = SparepartTaxonomyView["dimension"];
type Filters = {
  categoryId: string | null;
  brandId: string | null;
  kindId: string | null;
  typeId: string | null;
  search: string;
  machineCode: string;
};
type DimensionKey = "CATEGORY" | "KIND" | "BRAND" | "TYPE";

const ALL = "__all__";
const EMPTY_FORM: SparepartRequest = {
  machineId: "",
  categoryId: "",
  brandId: "",
  kindId: "",
  typeId: "",
};
type ProcurementDraft = { materialCode: string; leadTimeHours: string };
const EMPTY_PROCUREMENT: ProcurementDraft = { materialCode: "", leadTimeHours: "" };
const EMPTY_PRICE_DRAFT: CurrencyPriceValue = { amount: "", currency: "IDR", kursToIdr: "" };

export function SparepartManagement() {
  const t = useTranslations("masterData");
  const tc = useTranslations("common");
  const te = useTranslations("errors");
  const user = useAuthUser();
  const queryClient = useQueryClient();
  const canMutate = user?.applicationRole === "SUPER_ADMIN" || user?.applicationRole === "MANAGER_MAINTENANCE";
  const [filters, setFilters] = useState<Filters>({
    categoryId: null,
    brandId: null,
    kindId: null,
    typeId: null,
    search: "",
    machineCode: "",
  });
  const [machineSearch, setMachineSearch] = useState("");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [sort, setSort] = useState("");
  const taxonomy = useListSparepartTaxonomies();
  const machines = useListMachines({ search: machineSearch.trim() || undefined, page: 0, size: 50 });
  const spareparts = useListSpareparts({
    ...cleanFilters(filters),
    pageable: { page, size, sort: sort ? [sort] : undefined },
  });
  const createSparepart = useCreateSparepart({ mutation: { onSuccess: invalidateSparepartData } });
  const createTaxonomy = useCreateSparepartTaxonomy({ mutation: { onSuccess: invalidateTaxonomyData } });
  const updateSparepart = useUpdateSparepart({ mutation: { onSuccess: invalidateSparepartData } });
  const patchSparepartProcurement = usePatchSparepartProcurement({
    mutation: { onSuccess: invalidateSparepartData },
  });
  const deleteSparepart = useDeleteSparepart({ mutation: { onSuccess: invalidateSparepartData } });
  const createPriceEntry = useCreateSparepartPriceEntries();
  const [dialogMode, setDialogMode] = useState<{ type: "create" } | { type: "edit"; sparepart: SparepartView } | null>(
    null,
  );
  const [step, setStep] = useState(1);
  const [form, setForm] = useState<SparepartRequest>(EMPTY_FORM);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<SparepartView | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [procurement, setProcurement] = useState<ProcurementDraft>(EMPTY_PROCUREMENT);
  const [procurementOriginal, setProcurementOriginal] = useState<ProcurementDraft>(EMPTY_PROCUREMENT);
  const [priceDraft, setPriceDraft] = useState<CurrencyPriceValue>(EMPTY_PRICE_DRAFT);
  const [priceError, setPriceError] = useState<string | null>(null);
  const [imageError, setImageError] = useState<string | null>(null);
  const historySparepartId = dialogMode?.type === "edit" ? dialogMode.sparepart.id : undefined;
  const priceHistory = useListSparepartPriceEntries(historySparepartId ?? "", {
    query: { enabled: Boolean(historySparepartId) },
  });
  const imageQuery = useGetSparepartImage(historySparepartId ?? "", {
    query: { enabled: Boolean(historySparepartId) },
  });
  const createImage = useCreateSparepartImage({
    mutation: {
      onSuccess: (_data, variables) => {
        queryClient.invalidateQueries({ queryKey: getGetSparepartImageQueryKey(variables.sparepartId) });
      },
    },
  });
  const deleteImage = useDeleteSparepartImage({
    mutation: {
      onSuccess: (_data, variables) => {
        queryClient.invalidateQueries({ queryKey: getGetSparepartImageQueryKey(variables.sparepartId) });
      },
    },
  });
  const priceHistoryItems: SparepartPriceEntryView[] = priceHistory.data?.data ?? [];
  const imageView = imageQuery.data?.data;
  const imageLoadError = errorResponse(imageQuery.error);
  const imageMissing = imageQuery.isError && imageLoadError?.code === "SPAREPART_IMAGE_NOT_FOUND";
  const imageLoadFailed = imageQuery.isError && !imageMissing;
  const taxonomyItems = taxonomy.data?.data.items ?? [];
  const taxonomyByDimension = useMemo(() => groupByDimension(taxonomyItems), [taxonomyItems]);
  const machineItems = machines.data?.data.items ?? [];
  const items = spareparts.data?.data.items ?? [];
  const isSaving = createSparepart.isPending || updateSparepart.isPending || patchSparepartProcurement.isPending;
  const taxonomyReady = hasRequiredTaxonomy(taxonomyByDimension);

  function invalidateSparepartData() {
    queryClient.invalidateQueries({ queryKey: getListSparepartsQueryKey() });
  }

  function invalidateTaxonomyData() {
    queryClient.invalidateQueries({ queryKey: getListSparepartTaxonomiesQueryKey() });
  }

  async function createTaxonomyValue(dimension: SparepartTaxonomyRequestDimension, name: string) {
    const response = await createTaxonomy.mutateAsync({
      data: {
        dimension,
        code: taxonomyCode(name),
        name: name.trim(),
        categoryId: form.categoryId,
      },
    });
    await queryClient.invalidateQueries({ queryKey: getListSparepartTaxonomiesQueryKey() });
    if (response.data.id) {
      setForm((current) => ({ ...current, [formFieldForDimension(dimension)]: response.data.id ?? "" }));
    }
    toast.success(t(`sparepart.createToast.${dimension}`));
  }

  function openCreateDialog() {
    setDialogMode({ type: "create" });
    setForm(EMPTY_FORM);
    setProcurement(EMPTY_PROCUREMENT);
    setProcurementOriginal(EMPTY_PROCUREMENT);
    setPriceDraft(EMPTY_PRICE_DRAFT);
    setPriceError(null);
    setImageError(null);
    setFieldErrors({});
    setFormError(null);
    setStep(1);
  }

  function procurementDraftFor(sparepart: SparepartView): ProcurementDraft {
    return {
      materialCode: sparepart.materialCode ?? "",
      leadTimeHours: sparepart.leadTimeHours != null ? String(sparepart.leadTimeHours) : "",
    };
  }

  function openEditDialog(sparepart: SparepartView) {
    setDialogMode({ type: "edit", sparepart });
    setForm({
      machineId: sparepart.machine?.id ?? "",
      categoryId: sparepart.category?.id ?? "",
      brandId: sparepart.brand?.id ?? "",
      kindId: sparepart.kind?.id ?? "",
      typeId: sparepart.type?.id ?? "",
    });
    const draft = procurementDraftFor(sparepart);
    setProcurement(draft);
    setProcurementOriginal(draft);
    setPriceDraft(EMPTY_PRICE_DRAFT);
    setPriceError(null);
    setImageError(null);
    setFieldErrors({});
    setFormError(null);
    setStep(1);
  }

  function procurementChanged(): boolean {
    return (
      procurement.materialCode.trim() !== procurementOriginal.materialCode ||
      procurement.leadTimeHours.trim() !== procurementOriginal.leadTimeHours
    );
  }

  /** UX-level guidance only; the backend remains authoritative. */
  function validateProcurementDraft(): Record<string, string> | null {
    const errors: Record<string, string> = {};
    const materialCode = procurement.materialCode.trim();
    if (materialCode.length > 64) {
      errors.materialCode = t("sparepart.procurement.materialCodeMax");
    }
    const hours = procurement.leadTimeHours.trim();
    const hoursValid = hours === "" || (/^\d+(\.\d{1,2})?$/.test(hours) && Number(hours) > 0);
    if (!hoursValid) {
      errors.leadTimeHours = t("sparepart.procurement.leadTimeInvalid");
    }
    return Object.keys(errors).length > 0 ? errors : null;
  }

  function procurementPayload(): SparepartProcurementRequest {
    // Omitted keys clear values per the PATCH contract, so undefined == clear here.
    const payload: SparepartProcurementRequest = {};
    const materialCode = procurement.materialCode.trim();
    if (materialCode !== "") {
      payload.materialCode = materialCode;
    }
    if (procurement.leadTimeHours.trim() !== "") {
      payload.leadTimeHours = Number(procurement.leadTimeHours.trim());
    }
    return payload;
  }

  async function submitSparepart(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFieldErrors({});
    setFormError(null);

    const procurementErrors = dialogMode?.type === "edit" ? validateProcurementDraft() : null;
    if (procurementErrors) {
      setFieldErrors(procurementErrors);
      return;
    }

    try {
      const finalForm = { ...form };
      const pendingTaxonomies = [
        { key: "brandId" as const, dim: SparepartTaxonomyRequestDimension.BRAND, name: finalForm.brandId },
        { key: "kindId" as const, dim: SparepartTaxonomyRequestDimension.KIND, name: finalForm.kindId },
        { key: "typeId" as const, dim: SparepartTaxonomyRequestDimension.TYPE, name: finalForm.typeId },
      ];

      for (const taxo of pendingTaxonomies) {
        if (taxo.name.startsWith("pending-")) {
          const actualName = taxo.name.replace("pending-", "");
          const res = await createTaxonomy.mutateAsync({
            data: {
              dimension: taxo.dim,
              code: taxonomyCode(actualName),
              name: actualName.trim(),
              categoryId: finalForm.categoryId,
            },
          });
          finalForm[taxo.key] = res.data.id ?? "";
        }
      }

      await queryClient.invalidateQueries({ queryKey: getListSparepartTaxonomiesQueryKey() });

      if (dialogMode?.type === "edit") {
        if (!dialogMode.sparepart.id) {
          setFormError(t("sparepart.toast.missingIdOnUpdate"));
          return;
        }
        await updateSparepart.mutateAsync({ sparepartId: dialogMode.sparepart.id, data: finalForm });
        if (procurementChanged()) {
          try {
            await patchSparepartProcurement.mutateAsync({
              sparepartId: dialogMode.sparepart.id,
              data: procurementPayload(),
            });
          } catch (patchError) {
            // The base sparepart update already committed; the procurement change did not.
            // Persist the denial inline (toast alone disappears) and keep the dialog open
            // so the operator sees what happened and can retry the procurement save.
            const response = errorResponse(patchError);
            const detail = response ? apiErrorMessage(te, response) : t("sparepart.procurement.saveFailedDetail");
            const message = t("sparepart.procurement.notSaved", { message: detail });
            toast.error(message);
            setFormError(message);
            return;
          }
        }
        toast.success(t("sparepart.toast.updated"));
      } else {
        await createSparepart.mutateAsync({ data: finalForm });
        toast.success(t("sparepart.toast.created"));
      }
      setDialogMode(null);
    } catch (error) {
      const response = errorResponse(error);
      setFieldErrors(response?.fieldErrors ?? {});
      const message = response ? apiErrorMessage(te, response) : t("sparepart.toast.requestFailed");
      setFormError(message);
      toast.error(message);
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) {
      return;
    }
    setDeleteError(null);

    try {
      if (!deleteTarget.id) {
        setDeleteError(t("sparepart.toast.missingIdOnDelete"));
        return;
      }
      await deleteSparepart.mutateAsync({ sparepartId: deleteTarget.id });
      toast.success(t("sparepart.toast.deleted"));
      setDeleteTarget(null);
    } catch (error) {
      const response = errorResponse(error);
      const message = response ? apiErrorMessage(te, response) : t("sparepart.toast.deleteFailed");
      setDeleteError(message);
      toast.error(message);
    }
  }

  async function submitPriceEntry() {
    const sparepartId = dialogMode?.type === "edit" ? dialogMode.sparepart.id : undefined;
    if (!sparepartId || createPriceEntry.isPending) {
      return;
    }
    const amount = Number(priceDraft.amount.trim());
    if (!Number.isFinite(amount) || amount <= 0) {
      setFieldErrors((current) => ({ ...current, amount: t("sparepart.price.positiveAmount") }));
      return;
    }
    setFieldErrors((current) => {
      const next = { ...current };
      delete next.amount;
      delete next.currency;
      delete next.kursToIdr;
      return next;
    });
    setPriceError(null);
    try {
      await createPriceEntry.mutateAsync({
        sparepartId,
        data: {
          amount,
          currency: priceDraft.currency,
          kursToIdr: priceDraft.currency === "IDR" ? undefined : Number(priceDraft.kursToIdr),
        },
      });
      await queryClient.invalidateQueries({
        queryKey: getListSparepartPriceEntriesQueryKey(sparepartId),
      });
      setPriceDraft(EMPTY_PRICE_DRAFT);
      toast.success(t("sparepart.price.appended"));
    } catch (error) {
      const response = errorResponse(error);
      setFieldErrors(response?.fieldErrors ?? {});
      const message = response ? apiErrorMessage(te, response) : t("sparepart.price.appendFailed");
      setPriceError(message);
      toast.error(message);
    }
  }

  function reusePriceEntry(entry: SparepartPriceEntryView) {
    setPriceDraft({
      amount: entry.amount != null ? String(entry.amount) : "",
      currency: entry.currency ?? "IDR",
      kursToIdr: entry.kursToIdr != null ? String(entry.kursToIdr) : "",
    });
  }

  async function uploadImage(file: File) {
    const sparepartId = dialogMode?.type === "edit" ? dialogMode.sparepart.id : undefined;
    if (!sparepartId || createImage.isPending) {
      return;
    }
    setImageError(null);
    try {
      await createImage.mutateAsync({
        sparepartId,
        params: { filename: file.name, contentType: file.type },
        data: { data: file },
      });
      toast.success(t("sparepart.image.uploaded"));
    } catch (error) {
      const response = errorResponse(error);
      const message = response ? apiErrorMessage(te, response) : t("sparepart.image.uploadFailed");
      setImageError(message);
      toast.error(message);
    }
  }

  async function removeImage() {
    const sparepartId = dialogMode?.type === "edit" ? dialogMode.sparepart.id : undefined;
    if (!sparepartId || deleteImage.isPending) {
      return;
    }
    setImageError(null);
    try {
      await deleteImage.mutateAsync({ sparepartId });
      toast.success(t("sparepart.image.removed"));
    } catch (error) {
      const response = errorResponse(error);
      const message = response ? apiErrorMessage(te, response) : t("sparepart.image.removeFailed");
      setImageError(message);
      toast.error(message);
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("sparepart.title")}</CardTitle>
        <CardDescription>{t("sparepart.description")}</CardDescription>
        <CardAction>
          <div className="flex items-center gap-2">
            {canMutate ? null : <Badge variant="secondary">{t("sparepart.readOnly")}</Badge>}
            {canMutate ? (
              <Button onClick={openCreateDialog} disabled={!taxonomyReady || taxonomy.isLoading}>
                {t("sparepart.create")}
              </Button>
            ) : null}
          </div>
        </CardAction>
      </CardHeader>
      <CardContent className="space-y-4">
        {taxonomy.isLoading || spareparts.isLoading ? <SparepartSkeleton /> : null}
        {taxonomy.isError ? (
          <SparepartState
            title={t("sparepart.state.taxonomyLoadTitle")}
            description={t("sparepart.state.taxonomyLoadDesc")}
            action={
              <Button variant="outline" onClick={() => taxonomy.refetch()}>
                {t("sparepart.state.retryTaxonomy")}
              </Button>
            }
          />
        ) : null}
        {!taxonomy.isLoading && !taxonomy.isError && !taxonomyReady ? (
          <SparepartState
            title={t("sparepart.state.taxonomyIncompleteTitle")}
            description={t("sparepart.state.taxonomyIncompleteDesc")}
          />
        ) : null}
        {spareparts.isError ? (
          <SparepartState
            title={t("sparepart.state.loadFailedTitle")}
            description={t("sparepart.state.loadFailedDesc")}
            action={
              <Button variant="outline" onClick={() => spareparts.refetch()}>
                {t("sparepart.state.retrySpareparts")}
              </Button>
            }
          />
        ) : null}
        {!taxonomy.isLoading && !taxonomy.isError && taxonomyReady ? (
          <SparepartFilters
            filters={filters}
            taxonomyByDimension={taxonomyByDimension}
            onChange={(newFilters) => {
              setFilters(newFilters);
              setPage(0);
            }}
          />
        ) : null}
        {!spareparts.isLoading && !spareparts.isError && taxonomyReady && items.length === 0 ? (
          <SparepartState title={t("sparepart.state.emptyTitle")} description={t("sparepart.state.emptyDesc")} />
        ) : null}
        {!spareparts.isLoading && !spareparts.isError && taxonomyReady && items.length > 0 ? (
          <SparepartTable
            items={items}
            canMutate={canMutate}
            onEdit={openEditDialog}
            onDelete={(sparepart) => {
              setDeleteError(null);
              setDeleteTarget(sparepart);
            }}
            sort={sort}
            setSort={setSort}
          />
        ) : null}
        {!spareparts.isLoading && !spareparts.isError && spareparts.data?.data ? (
          <DataTablePagination
            page={page}
            size={size}
            totalElements={spareparts.data.data.totalElements}
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
          <form onSubmit={submitSparepart}>
            <DialogHeader>
              <DialogTitle>
                {dialogMode?.type === "edit" ? t("sparepart.dialog.editTitle") : t("sparepart.dialog.createTitle")}
              </DialogTitle>
              <DialogDescription>{t("sparepart.dialog.description")}</DialogDescription>
            </DialogHeader>
            {formError ? (
              <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">{formError}</p>
            ) : null}

            <div className="flex gap-2 items-center text-sm font-medium py-2">
              <span className={step === 1 ? "text-primary" : "text-muted-foreground"}>
                {t("sparepart.step.details")}
              </span>
              <span className="text-muted-foreground">/</span>
              <span className={step === 2 ? "text-primary" : "text-muted-foreground"}>
                {t("sparepart.step.confirmation")}
              </span>
            </div>

            <div className={step === 1 ? "grid gap-4 md:grid-cols-2" : "hidden"}>
              <MachineSelect
                value={form.machineId}
                search={machineSearch}
                error={fieldErrors.machineId}
                disabled={isSaving}
                items={machineItems}
                onSearchChange={setMachineSearch}
                onChange={(machineId) => setForm((current) => ({ ...current, machineId }))}
              />
              <TaxonomySelect
                dimension="CATEGORY"
                value={form.categoryId}
                error={fieldErrors.categoryId}
                disabled={isSaving}
                items={taxonomyByDimension.get(SparepartTaxonomyRequestDimension.CATEGORY) ?? []}
                creatable={false}
                onCreate={undefined}
                onChange={(categoryId) =>
                  setForm((current) => ({ ...current, categoryId, brandId: "", kindId: "", typeId: "" }))
                }
              />
              <TaxonomySelect
                dimension="KIND"
                value={form.kindId}
                error={fieldErrors.kindId}
                disabled={isSaving}
                items={linkedTaxonomyOptions(
                  taxonomyByDimension,
                  SparepartTaxonomyRequestDimension.KIND,
                  form.categoryId,
                )}
                creatable={!isSaving && Boolean(form.categoryId)}
                onCreate={(name) => Promise.resolve(setForm((current) => ({ ...current, kindId: `pending-${name}` })))}
                onChange={(kindId) => setForm((current) => ({ ...current, kindId }))}
              />
              <TaxonomySelect
                dimension="BRAND"
                value={form.brandId}
                error={fieldErrors.brandId}
                disabled={isSaving}
                items={linkedTaxonomyOptions(
                  taxonomyByDimension,
                  SparepartTaxonomyRequestDimension.BRAND,
                  form.categoryId,
                )}
                creatable={!isSaving && Boolean(form.categoryId)}
                onCreate={(name) => Promise.resolve(setForm((current) => ({ ...current, brandId: `pending-${name}` })))}
                onChange={(brandId) => setForm((current) => ({ ...current, brandId }))}
              />
              <TaxonomySelect
                dimension="TYPE"
                value={form.typeId}
                error={fieldErrors.typeId}
                disabled={isSaving}
                items={linkedTaxonomyOptions(
                  taxonomyByDimension,
                  SparepartTaxonomyRequestDimension.TYPE,
                  form.categoryId,
                )}
                creatable={!isSaving && Boolean(form.categoryId)}
                onCreate={(name) => Promise.resolve(setForm((current) => ({ ...current, typeId: `pending-${name}` })))}
                onChange={(typeId) => setForm((current) => ({ ...current, typeId }))}
              />
              {dialogMode?.type === "edit" ? (
                <div className="grid gap-4 rounded-md border border-dashed p-3 md:col-span-2">
                  <div>
                    <span className="font-medium text-sm">{t("sparepart.procurement.title")}</span>
                    <p className="text-muted-foreground text-xs">{t("sparepart.procurement.hint")}</p>
                  </div>
                  <MaterialCodeField
                    value={procurement.materialCode}
                    error={fieldErrors.materialCode}
                    onChange={(materialCode) => setProcurement((current) => ({ ...current, materialCode }))}
                  />
                  <LeadTimeInput
                    key={dialogMode?.type === "edit" ? (dialogMode.sparepart.id ?? "new") : "new"}
                    value={procurement.leadTimeHours}
                    error={fieldErrors.leadTimeHours}
                    onChange={(leadTimeHours) => setProcurement((current) => ({ ...current, leadTimeHours }))}
                  />
                </div>
              ) : null}
              {dialogMode?.type === "edit" ? (
                <div className="grid gap-4 rounded-md border border-dashed p-3 md:col-span-2">
                  <div>
                    <span className="font-medium text-sm">{t("sparepart.price.title")}</span>
                    <p className="text-muted-foreground text-xs">{t("sparepart.price.hint")}</p>
                  </div>
                  {priceError ? (
                    <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">{priceError}</p>
                  ) : null}
                  <div className="grid gap-3">
                    <CurrencyPriceInput
                      value={priceDraft}
                      errors={{
                        amount: fieldErrors.amount,
                        currency: fieldErrors.currency,
                        kursToIdr: fieldErrors.kursToIdr,
                      }}
                      onChange={setPriceDraft}
                    />
                    <Button
                      type="button"
                      variant="secondary"
                      className="justify-self-start"
                      disabled={createPriceEntry.isPending}
                      onClick={() => void submitPriceEntry()}
                    >
                      {createPriceEntry.isPending ? <Loader2Icon className="animate-spin" /> : null}
                      {t("sparepart.price.append")}
                    </Button>
                  </div>
                  <PriceHistoryTable
                    entries={priceHistoryItems}
                    isLoading={priceHistory.isLoading}
                    onReuse={reusePriceEntry}
                  />
                </div>
              ) : null}
              {dialogMode?.type === "edit" ? (
                <div
                  className="grid gap-4 rounded-md border border-dashed p-3 md:col-span-2"
                  data-testid="sparepart-image-section"
                >
                  <div>
                    <span className="font-medium text-sm">{t("sparepart.image.title")}</span>
                    <p className="text-muted-foreground text-xs">{t("sparepart.image.hint")}</p>
                  </div>
                  <SparepartImageUpload
                    value={imageView?.presignedUrl ?? null}
                    onUpload={(file) => void uploadImage(file)}
                    onRemove={() => void removeImage()}
                    isUploading={createImage.isPending}
                    isRemoving={deleteImage.isPending}
                    error={imageError ?? (imageLoadFailed ? t("sparepart.image.loadFailed") : undefined)}
                    readOnly={!canMutate}
                    disabledReason={t("sparepart.image.viewOnlyReason")}
                  />
                </div>
              ) : null}
            </div>

            {step === 2 && (
              <div className="grid gap-2 rounded-md border border-dashed p-3 text-sm min-h-[4.5rem] my-4">
                <span className="font-medium">{t("sparepart.confirm.title")}</span>
                <span className="text-muted-foreground">{t("sparepart.confirm.hint")}</span>
              </div>
            )}

            <DialogFooter className="mt-4">
              <Button type="button" variant="outline" onClick={() => setDialogMode(null)} disabled={isSaving}>
                {tc("cancel")}
              </Button>
              {step === 1 ? (
                <Button type="button" onClick={() => setStep(2)}>
                  {tc("next")}
                </Button>
              ) : (
                <>
                  <Button type="button" variant="secondary" onClick={() => setStep(1)} disabled={isSaving}>
                    {tc("back")}
                  </Button>
                  <Button type="submit" disabled={isSaving}>
                    {isSaving ? <Loader2Icon className="animate-spin" /> : null}
                    {t("sparepart.dialog.save")}
                  </Button>
                </>
              )}
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <AlertDialog open={deleteTarget !== null} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("sparepart.delete.title")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("sparepart.delete.description", { code: deleteTarget?.code ?? "" })}
            </AlertDialogDescription>
            {deleteError ? (
              <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">{deleteError}</p>
            ) : null}
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleteSparepart.isPending}>{tc("cancel")}</AlertDialogCancel>
            <Button variant="destructive" onClick={confirmDelete} disabled={deleteSparepart.isPending}>
              {deleteSparepart.isPending ? <Loader2Icon className="animate-spin" /> : null}
              {t("sparepart.delete.action")}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  );
}

function SparepartFilters({
  filters,
  taxonomyByDimension,
  onChange,
}: {
  filters: Filters;
  taxonomyByDimension: Map<TaxonomyDimension, SparepartTaxonomyView[]>;
  onChange: (filters: Filters) => void;
}) {
  const t = useTranslations("masterData");
  return (
    <div className="grid gap-3 rounded-lg border p-3 sm:grid-cols-[repeat(auto-fit,14rem)] sm:justify-start">
      <div className="relative w-full">
        <SearchIcon className="absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          className="pl-9"
          placeholder={t("sparepart.filter.searchCode")}
          value={filters.search}
          onChange={(event) => onChange({ ...filters, search: event.target.value })}
        />
      </div>
      <div className="relative w-full">
        <SearchIcon className="absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          className="pl-9"
          placeholder={t("sparepart.filter.machineCode")}
          value={filters.machineCode}
          onChange={(event) => onChange({ ...filters, machineCode: event.target.value })}
        />
      </div>
      <FilterSelect
        dimension="CATEGORY"
        value={filters.categoryId ?? undefined}
        items={taxonomyByDimension.get(SparepartTaxonomyRequestDimension.CATEGORY) ?? []}
        onChange={(categoryId) => onChange({ ...filters, categoryId: categoryId ?? null })}
      />
      <FilterSelect
        dimension="BRAND"
        value={filters.brandId ?? undefined}
        items={linkedTaxonomyOptions(
          taxonomyByDimension,
          SparepartTaxonomyRequestDimension.BRAND,
          filters.categoryId ?? "",
        )}
        onChange={(brandId) => onChange({ ...filters, brandId: brandId ?? null })}
      />
      <FilterSelect
        dimension="KIND"
        value={filters.kindId ?? undefined}
        items={linkedTaxonomyOptions(
          taxonomyByDimension,
          SparepartTaxonomyRequestDimension.KIND,
          filters.categoryId ?? "",
        )}
        onChange={(kindId) => onChange({ ...filters, kindId: kindId ?? null })}
      />
      <FilterSelect
        dimension="TYPE"
        value={filters.typeId ?? undefined}
        items={linkedTaxonomyOptions(
          taxonomyByDimension,
          SparepartTaxonomyRequestDimension.TYPE,
          filters.categoryId ?? "",
        )}
        onChange={(typeId) => onChange({ ...filters, typeId: typeId ?? null })}
      />
    </div>
  );
}

function SparepartTable({
  items,
  canMutate,
  onEdit,
  onDelete,
  sort,
  setSort,
}: {
  items: SparepartView[];
  canMutate: boolean;
  onEdit: (sparepart: SparepartView) => void;
  onDelete: (sparepart: SparepartView) => void;
  sort: string;
  setSort: (sort: string) => void;
}) {
  const t = useTranslations("masterData");
  const tc = useTranslations("common");
  const dt = useDateTimeFormatter();
  return (
    <div className="overflow-x-auto rounded-lg border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="whitespace-nowrap">
              <DataTableSortHeader title={tc("code")} field="code" sort={sort} onSortChange={setSort} />
            </TableHead>
            <TableHead className="whitespace-nowrap min-w-[200px]">
              <DataTableSortHeader
                title={t("sparepart.machine")}
                field="machine.code"
                sort={sort}
                onSortChange={setSort}
              />
            </TableHead>
            <TableHead className="whitespace-nowrap">
              <DataTableSortHeader
                title={t("sparepart.dimension.CATEGORY")}
                field="category.name"
                sort={sort}
                onSortChange={setSort}
              />
            </TableHead>
            <TableHead className="whitespace-nowrap">
              <DataTableSortHeader
                title={t("sparepart.dimension.KIND")}
                field="kind.name"
                sort={sort}
                onSortChange={setSort}
              />
            </TableHead>
            <TableHead className="whitespace-nowrap">
              <DataTableSortHeader
                title={t("sparepart.dimension.BRAND")}
                field="brand.name"
                sort={sort}
                onSortChange={setSort}
              />
            </TableHead>
            <TableHead className="whitespace-nowrap">
              <DataTableSortHeader
                title={t("sparepart.dimension.TYPE")}
                field="type.name"
                sort={sort}
                onSortChange={setSort}
              />
            </TableHead>
            <TableHead className="whitespace-nowrap">{t("sparepart.materialCode")}</TableHead>
            <TableHead className="whitespace-nowrap">{t("sparepart.leadTime")}</TableHead>
            <TableHead>{tc("createdAt")}</TableHead>
            <TableHead>{tc("updatedAt")}</TableHead>
            <TableHead className="text-right">{tc("actions")}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {items.map((sparepart) => (
            <TableRow key={sparepart.id ?? sparepart.code}>
              <TableCell className="font-mono text-xs">{sparepart.code}</TableCell>
              <TableCell>
                {sparepart.machine?.code ? `${sparepart.machine.name} (${sparepart.machine.code})` : "-"}
              </TableCell>
              <TableCell>{sparepart.category?.name ?? "-"}</TableCell>
              <TableCell>{sparepart.kind?.name ?? "-"}</TableCell>
              <TableCell>{sparepart.brand?.name ?? "-"}</TableCell>
              <TableCell>{sparepart.type?.name ?? "-"}</TableCell>
              <TableCell className="font-mono text-xs">{sparepart.materialCode ?? "-"}</TableCell>
              <TableCell>
                {sparepart.leadTimeHours != null
                  ? t("sparepart.leadTimeValue", { hours: sparepart.leadTimeHours })
                  : "-"}
              </TableCell>
              <TableCell>{sparepart.createdAt ? dt.dateTime(sparepart.createdAt) : "-"}</TableCell>
              <TableCell>{sparepart.updatedAt ? dt.dateTime(sparepart.updatedAt) : "-"}</TableCell>
              <TableCell className="text-right">
                {canMutate ? (
                  <div className="flex justify-end gap-2">
                    <Button variant="outline" size="sm" onClick={() => onEdit(sparepart)}>
                      {tc("edit")}
                    </Button>
                    <Button variant="destructive" size="sm" onClick={() => onDelete(sparepart)}>
                      <Trash2 />
                      {tc("delete")}
                    </Button>
                  </div>
                ) : (
                  <Badge variant="secondary">{t("sparepart.viewOnly")}</Badge>
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
  value?: string;
  search: string;
  error?: string;
  disabled?: boolean;
  items: MachineView[];
  onSearchChange: (value: string) => void;
  onChange: (value: string) => void;
}) {
  const t = useTranslations("masterData");
  return (
    <div className="grid gap-2">
      <Label>{t("sparepart.machine")}</Label>
      <Select value={value} onValueChange={onChange} disabled={Boolean(disabled) || items.length === 0}>
        <SelectTrigger className="w-full min-w-0" aria-invalid={Boolean(error)}>
          <SelectValue placeholder={t("sparepart.selectMachine")} />
        </SelectTrigger>
        <SelectContent>
          <div className="p-2">
            <Input
              value={search}
              placeholder={t("sparepart.searchMachine")}
              onChange={(event) => onSearchChange(event.target.value)}
              onKeyDown={(event) => event.stopPropagation()}
            />
          </div>
          {items.length === 0 ? (
            <div className="px-2 py-1.5 text-muted-foreground text-sm">{t("sparepart.noMachinesFound")}</div>
          ) : null}
          {items.map((machine) => (
            <SelectItem key={machine.id ?? machine.code} value={machine.id ?? ""}>
              {machine.code} · {machine.name || t("sparepart.unnamed")} · {machine.plantCode}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      {error ? <p className="text-destructive text-sm">{error}</p> : null}
    </div>
  );
}

function TaxonomySelect({
  dimension,
  value,
  error,
  disabled,
  items,
  creatable,
  onCreate,
  onChange,
}: {
  dimension: DimensionKey;
  value?: string;
  error?: string;
  disabled?: boolean;
  items: SparepartTaxonomyView[];
  creatable?: boolean;
  onCreate?: (name: string) => Promise<void>;
  onChange: (value: string) => void;
}) {
  const t = useTranslations("masterData");
  const [search, setSearch] = useState("");
  const visibleItems = items.filter((item) => taxonomyMatches(item, search));
  const canCreate = Boolean(creatable && onCreate && search.trim());

  async function submitCreate() {
    if (!onCreate || !search.trim()) {
      return;
    }
    await onCreate(search);
    setSearch("");
  }

  return (
    <div className="grid gap-2">
      <Label>{t(`sparepart.dimension.${dimension}`)}</Label>
      <Select value={value} onValueChange={onChange} disabled={Boolean(disabled) || (!creatable && items.length === 0)}>
        <SelectTrigger className="w-full min-w-0" aria-invalid={Boolean(error)}>
          <SelectValue placeholder={t(`sparepart.select.${dimension}`)} />
        </SelectTrigger>
        <SelectContent position="popper" side="top" align="start" className="max-h-72">
          <div className="p-2">
            <Input
              value={search}
              placeholder={t(`sparepart.search.${dimension}`)}
              onChange={(event) => setSearch(event.target.value)}
              onKeyDown={(event) => event.stopPropagation()}
            />
          </div>
          {visibleItems.length === 0 ? (
            <div className="px-2 py-1.5 text-muted-foreground text-sm">{t(`sparepart.noFound.${dimension}`)}</div>
          ) : null}
          {visibleItems.map((item) => (
            <SelectItem key={item.id} value={item.id ?? ""}>
              {item.name} ({item.code})
            </SelectItem>
          ))}
          {canCreate ? (
            <div className="border-t p-2">
              <Button
                type="button"
                variant="outline"
                className="w-full"
                size="sm"
                onClick={submitCreate}
                disabled={disabled}
              >
                {t(`sparepart.createLabel.${dimension}`, { input: search.trim() })}
              </Button>
            </div>
          ) : null}
        </SelectContent>
      </Select>
      {error ? <p className="text-destructive text-sm">{error}</p> : null}
    </div>
  );
}

function FilterSelect({
  dimension,
  value,
  items,
  onChange,
}: {
  dimension: DimensionKey;
  value?: string;
  items: SparepartTaxonomyView[];
  onChange: (value: string | undefined) => void;
}) {
  const t = useTranslations("masterData");
  const [search, setSearch] = React.useState("");
  const filtered = search.trim()
    ? items.filter((item) => [item.name, item.code].some((v) => v?.toLowerCase().includes(search.trim().toLowerCase())))
    : items;
  const selectedItem = items.find((i) => i.id === value);

  return (
    <Select
      value={value ?? ALL}
      onValueChange={(next) => {
        onChange(next === ALL ? undefined : next);
        setSearch("");
      }}
    >
      <SelectTrigger className="w-full min-w-0">
        <SelectValue placeholder={t(`sparepart.all.${dimension}`)}>
          {selectedItem ? selectedItem.name : t(`sparepart.all.${dimension}`)}
        </SelectValue>
      </SelectTrigger>
      <SelectContent>
        <div className="p-2">
          <Input
            placeholder={t(`sparepart.searchDots.${dimension}`)}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            onClick={(e) => e.stopPropagation()}
            onKeyDown={(e) => e.stopPropagation()}
          />
        </div>
        <SelectItem value={ALL}>{t(`sparepart.all.${dimension}`)}</SelectItem>
        {filtered.map((item) => (
          <SelectItem key={item.id} value={item.id ?? ""}>
            {item.name}
          </SelectItem>
        ))}
        {filtered.length === 0 ? (
          <p className="px-2 py-3 text-center text-sm text-muted-foreground">{t("sparepart.noResults")}</p>
        ) : null}
      </SelectContent>
    </Select>
  );
}

function SparepartState({
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

function SparepartSkeleton() {
  return (
    <div className="space-y-3">
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-12 w-full" />
      <Skeleton className="h-12 w-full" />
      <Skeleton className="h-12 w-full" />
    </div>
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

function taxonomyMatches(item: SparepartTaxonomyView, search: string) {
  const normalized = search.trim().toLowerCase();
  if (!normalized) {
    return true;
  }
  return [item.code, item.name].some((value) => value?.toLowerCase().includes(normalized));
}

function taxonomyCode(name: string) {
  return name
    .trim()
    .toUpperCase()
    .replace(/[^A-Z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "");
}

function formFieldForDimension(dimension: SparepartTaxonomyRequestDimension): keyof SparepartRequest {
  if (dimension === SparepartTaxonomyRequestDimension.BRAND) {
    return "brandId";
  }
  if (dimension === SparepartTaxonomyRequestDimension.KIND) {
    return "kindId";
  }
  if (dimension === SparepartTaxonomyRequestDimension.TYPE) {
    return "typeId";
  }
  return "categoryId";
}

function hasRequiredTaxonomy(taxonomyByDimension: Map<TaxonomyDimension, SparepartTaxonomyView[]>) {
  return [
    SparepartTaxonomyRequestDimension.CATEGORY,
    SparepartTaxonomyRequestDimension.BRAND,
    SparepartTaxonomyRequestDimension.KIND,
    SparepartTaxonomyRequestDimension.TYPE,
  ].every((dimension) => (taxonomyByDimension.get(dimension) ?? []).length > 0);
}

function cleanFilters(filters: Filters) {
  return {
    categoryId: filters.categoryId ?? undefined,
    brandId: filters.brandId ?? undefined,
    kindId: filters.kindId ?? undefined,
    typeId: filters.typeId ?? undefined,
    search: filters.search?.trim() || undefined,
    machineCode: filters.machineCode?.trim() || undefined,
  };
}
