"use client";

import { type FormEvent, useState } from "react";

import { useQueryClient } from "@tanstack/react-query";
import { Loader2Icon, Trash2, TriangleAlertIcon } from "lucide-react";
import { useTranslations } from "next-intl";
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
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { apiErrorMessage, errorResponse } from "@/lib/api/error-response";
import type { SparepartTaxonomyRequest, SparepartTaxonomyView } from "@/lib/api/generated/model";
import { SparepartTaxonomyRequestDimension } from "@/lib/api/generated/model";
import {
  getListSparepartTaxonomiesQueryKey,
  useCreateSparepartTaxonomy,
  useDeleteSparepartTaxonomy,
  useListSparepartTaxonomies,
  useUpdateSparepartTaxonomy,
} from "@/lib/api/generated/syncro";
import { useAuthUser } from "@/lib/auth/use-auth-user";
import { useDateTimeFormatter } from "@/lib/i18n/format";

type TaxonomyFormState = Pick<SparepartTaxonomyRequest, "code" | "name">;
type DialogMode = { type: "create"; entry?: never } | { type: "edit"; entry: SparepartTaxonomyView };

export function SparepartTaxonomyManagement() {
  const t = useTranslations("masterData");
  const tc = useTranslations("common");
  const te = useTranslations("errors");
  const user = useAuthUser();
  const queryClient = useQueryClient();
  const canMutate = user?.applicationRole === "SUPER_ADMIN" || user?.applicationRole === "MANAGER_MAINTENANCE";
  const taxonomy = useListSparepartTaxonomies();
  const createTaxonomy = useCreateSparepartTaxonomy({ mutation: { onSuccess: invalidateTaxonomyData } });
  const updateTaxonomy = useUpdateSparepartTaxonomy({ mutation: { onSuccess: invalidateTaxonomyData } });
  const deleteTaxonomy = useDeleteSparepartTaxonomy({ mutation: { onSuccess: invalidateTaxonomyData } });
  const [dialogMode, setDialogMode] = useState<DialogMode | null>(null);
  const [form, setForm] = useState<TaxonomyFormState>({ code: "", name: "" });
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<SparepartTaxonomyView | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  // Only show Category entries
  const allItems = taxonomy.data?.data.items ?? [];
  const categoryItems = allItems.filter((item) => item.dimension === SparepartTaxonomyRequestDimension.CATEGORY);
  const isSaving = createTaxonomy.isPending || updateTaxonomy.isPending;

  function invalidateTaxonomyData() {
    queryClient.invalidateQueries({ queryKey: getListSparepartTaxonomiesQueryKey() });
  }

  function openCreateDialog() {
    setDialogMode({ type: "create" });
    setForm({ code: "", name: "" });
    setFieldErrors({});
    setFormError(null);
  }

  function openEditDialog(entry: SparepartTaxonomyView) {
    setDialogMode({ type: "edit", entry });
    setForm({ code: entry.code ?? "", name: entry.name ?? "" });
    setFieldErrors({});
    setFormError(null);
  }

  async function submitTaxonomy(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFieldErrors({});
    setFormError(null);

    try {
      if (dialogMode?.type === "edit") {
        await updateTaxonomy.mutateAsync({
          taxonomyId: dialogMode.entry.id ?? "",
          data: {
            dimension: SparepartTaxonomyRequestDimension.CATEGORY,
            code: form.code,
            name: form.name,
          },
        });
        toast.success(t("sparepartTaxonomy.toast.updated"));
      } else {
        await createTaxonomy.mutateAsync({
          data: {
            dimension: SparepartTaxonomyRequestDimension.CATEGORY,
            code: form.code,
            name: form.name,
          },
        });
        toast.success(t("sparepartTaxonomy.toast.created"));
      }
      setDialogMode(null);
    } catch (error) {
      const response = errorResponse(error);
      setFieldErrors(response?.fieldErrors ?? {});
      const message = response ? apiErrorMessage(te, response) : t("sparepartTaxonomy.toast.requestFailed");
      setFormError(message);
      toast.error(message);
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) return;
    setDeleteError(null);

    try {
      await deleteTaxonomy.mutateAsync({ taxonomyId: deleteTarget.id ?? "" });
      toast.success(t("sparepartTaxonomy.toast.deleted"));
      setDeleteTarget(null);
    } catch (error) {
      const response = errorResponse(error);
      const message = response ? apiErrorMessage(te, response) : t("sparepartTaxonomy.toast.deleteFailed");
      setDeleteError(message);
      toast.error(message);
    }
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>{t("sparepartTaxonomy.title")}</CardTitle>
          <CardDescription>{t("sparepartTaxonomy.description")}</CardDescription>
          <CardAction>
            <div className="flex items-center gap-2">
              {canMutate ? null : <Badge variant="secondary">{t("sparepartTaxonomy.readOnly")}</Badge>}
              {canMutate ? <Button onClick={openCreateDialog}>{t("sparepartTaxonomy.add")}</Button> : null}
            </div>
          </CardAction>
        </CardHeader>
        <CardContent>
          {taxonomy.isLoading ? <CategorySkeleton /> : null}
          {taxonomy.isError ? (
            <CategoryState
              title={t("sparepartTaxonomy.state.loadFailedTitle")}
              description={t("sparepartTaxonomy.state.loadFailedDesc")}
              action={
                <Button variant="outline" onClick={() => taxonomy.refetch()}>
                  {tc("retry")}
                </Button>
              }
            />
          ) : null}
          {!taxonomy.isLoading && !taxonomy.isError && categoryItems.length === 0 ? (
            <CategoryState
              title={t("sparepartTaxonomy.state.emptyTitle")}
              description={t("sparepartTaxonomy.state.emptyDesc")}
            />
          ) : null}
          {!taxonomy.isLoading && !taxonomy.isError && categoryItems.length > 0 ? (
            <CategoryTable
              items={categoryItems}
              canMutate={canMutate}
              onEdit={openEditDialog}
              onDelete={(entry) => {
                setDeleteError(null);
                setDeleteTarget(entry);
              }}
            />
          ) : null}
        </CardContent>
      </Card>

      <Dialog open={dialogMode !== null} onOpenChange={(open) => !open && setDialogMode(null)}>
        <DialogContent className="top-4 max-h-[calc(100svh-2rem)] translate-y-0 overflow-y-auto sm:max-w-2xl">
          <form onSubmit={submitTaxonomy} className="space-y-4">
            <DialogHeader>
              <DialogTitle>
                {dialogMode?.type === "edit"
                  ? t("sparepartTaxonomy.dialog.editTitle")
                  : t("sparepartTaxonomy.dialog.createTitle")}
              </DialogTitle>
              <DialogDescription>{t("sparepartTaxonomy.dialog.description")}</DialogDescription>
            </DialogHeader>
            {formError ? (
              <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">{formError}</p>
            ) : null}
            <div className="grid gap-2">
              <Label htmlFor="category-name">{tc("name")}</Label>
              <Input
                id="category-name"
                placeholder={t("sparepartTaxonomy.dialog.namePlaceholder")}
                value={form.name}
                onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
                aria-invalid={Boolean(fieldErrors.name)}
                disabled={isSaving}
              />
              {fieldErrors.name ? <p className="text-destructive text-sm">{fieldErrors.name}</p> : null}
            </div>
            <div className="grid gap-2">
              <Label htmlFor="category-code">{tc("code")}</Label>
              <Input
                id="category-code"
                placeholder={t("sparepartTaxonomy.dialog.codePlaceholder")}
                value={form.code}
                onChange={(event) => setForm((current) => ({ ...current, code: event.target.value }))}
                aria-invalid={Boolean(fieldErrors.code)}
                disabled={isSaving}
              />
              {fieldErrors.code ? <p className="text-destructive text-sm">{fieldErrors.code}</p> : null}
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setDialogMode(null)} disabled={isSaving}>
                {tc("cancel")}
              </Button>
              <Button type="submit" disabled={isSaving}>
                {isSaving ? <Loader2Icon className="animate-spin" /> : null}
                {dialogMode?.type === "edit"
                  ? t("sparepartTaxonomy.dialog.submitEdit")
                  : t("sparepartTaxonomy.dialog.submitCreate")}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <AlertDialog open={deleteTarget !== null} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("sparepartTaxonomy.delete.title")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("sparepartTaxonomy.delete.description", { name: deleteTarget?.name ?? "" })}
            </AlertDialogDescription>
            {deleteError ? (
              <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">{deleteError}</p>
            ) : null}
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleteTaxonomy.isPending}>{tc("cancel")}</AlertDialogCancel>
            <Button variant="destructive" onClick={confirmDelete} disabled={deleteTaxonomy.isPending}>
              {deleteTaxonomy.isPending ? <Loader2Icon className="animate-spin" /> : null}
              {t("sparepartTaxonomy.delete.action")}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

function CategoryTable({
  items,
  canMutate,
  onEdit,
  onDelete,
}: {
  items: SparepartTaxonomyView[];
  canMutate: boolean;
  onEdit: (entry: SparepartTaxonomyView) => void;
  onDelete: (entry: SparepartTaxonomyView) => void;
}) {
  const t = useTranslations("masterData");
  const tc = useTranslations("common");
  const dt = useDateTimeFormatter();
  return (
    <div className="overflow-x-auto rounded-lg border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="whitespace-nowrap">{tc("code")}</TableHead>
            <TableHead className="whitespace-nowrap">{tc("name")}</TableHead>
            <TableHead className="whitespace-nowrap">{tc("createdAt")}</TableHead>
            <TableHead className="text-right w-32">{tc("actions")}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {items.map((entry) => (
            <TableRow key={entry.id ?? `${entry.code}-${entry.name}`}>
              <TableCell className="font-mono text-xs">{entry.code}</TableCell>
              <TableCell className="font-medium">{entry.name}</TableCell>
              <TableCell>{entry.createdAt ? dt.dateTime(entry.createdAt) : "-"}</TableCell>
              <TableCell className="text-right">
                {canMutate ? (
                  <div className="flex justify-end gap-2">
                    <Button variant="outline" size="sm" onClick={() => onEdit(entry)}>
                      {tc("edit")}
                    </Button>
                    <Button variant="destructive" size="sm" onClick={() => onDelete(entry)}>
                      <Trash2 />
                      {tc("delete")}
                    </Button>
                  </div>
                ) : (
                  <Badge variant="secondary">{t("sparepartTaxonomy.viewOnly")}</Badge>
                )}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

function CategoryState({
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

function CategorySkeleton() {
  return (
    <div className="space-y-3">
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-12 w-full" />
      <Skeleton className="h-12 w-full" />
      <Skeleton className="h-12 w-full" />
    </div>
  );
}
