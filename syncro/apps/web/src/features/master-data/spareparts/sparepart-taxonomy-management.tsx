"use client";

import { type FormEvent, useState } from "react";

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
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { SparepartTaxonomyRequest, SparepartTaxonomyView } from "@/lib/api/generated/model";
import { SparepartTaxonomyRequestDimension } from "@/lib/api/generated/model";
import {
  getListSparepartTaxonomiesQueryKey,
  useCreateSparepartTaxonomy,
  useDeleteSparepartTaxonomy,
  useListSparepartTaxonomies,
  useUpdateSparepartTaxonomy,
} from "@/lib/api/generated/syncro";
import { SyncroApiError } from "@/lib/api/orval-mutator";
import { useAuthUser } from "@/lib/auth/use-auth-user";

type TaxonomyFormState = Pick<SparepartTaxonomyRequest, "code" | "name">;
type DialogMode = { type: "create"; entry?: never } | { type: "edit"; entry: SparepartTaxonomyView };
type ErrorResponse = { code: string; message: string; fieldErrors?: Record<string, string> };

export function SparepartTaxonomyManagement() {
  const user = useAuthUser();
  const queryClient = useQueryClient();
  const canMutate = user?.applicationRole === "SUPER_ADMIN" || user?.applicationRole === "MANAGE";
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
        toast.success("Category updated.");
      } else {
        await createTaxonomy.mutateAsync({
          data: {
            dimension: SparepartTaxonomyRequestDimension.CATEGORY,
            code: form.code,
            name: form.name,
          },
        });
        toast.success("Category created.");
      }
      setDialogMode(null);
    } catch (error) {
      const response = errorResponse(error);
      setFieldErrors(response?.fieldErrors ?? {});
      setFormError(response?.message ?? "Category request failed.");
      toast.error(response?.message ?? "Category request failed.");
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) return;
    setDeleteError(null);

    try {
      await deleteTaxonomy.mutateAsync({ taxonomyId: deleteTarget.id ?? "" });
      toast.success("Category deleted.");
      setDeleteTarget(null);
    } catch (error) {
      const message = errorResponse(error)?.message ?? "Category delete failed.";
      setDeleteError(message);
      toast.error(message);
    }
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Category</CardTitle>
          <CardDescription>
            Manage controlled sparepart category values. Category determines which spareparts belong to which domain
            (e.g. Electric, Mechanic). Only approved categories are accepted by the system.
          </CardDescription>
          <CardAction>
            <div className="flex items-center gap-2">
              {canMutate ? null : <Badge variant="secondary">Read-only</Badge>}
              {canMutate ? <Button onClick={openCreateDialog}>Add category</Button> : null}
            </div>
          </CardAction>
        </CardHeader>
        <CardContent>
          {taxonomy.isLoading ? <CategorySkeleton /> : null}
          {taxonomy.isError ? (
            <CategoryState
              title="Categories could not be loaded"
              description="Refresh page or contact administrator if access should be available."
              action={
                <Button variant="outline" onClick={() => taxonomy.refetch()}>
                  Retry
                </Button>
              }
            />
          ) : null}
          {!taxonomy.isLoading && !taxonomy.isError && categoryItems.length === 0 ? (
            <CategoryState title="No categories yet" description="Create the first sparepart category entry." />
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
        <DialogContent>
          <form onSubmit={submitTaxonomy} className="space-y-4">
            <DialogHeader>
              <DialogTitle>{dialogMode?.type === "edit" ? "Edit category" : "Add category"}</DialogTitle>
              <DialogDescription>
                Category code must match a backend-approved value (e.g. ELECTRIC, MECHANIC). Names must be unique within
                Category.
              </DialogDescription>
            </DialogHeader>
            {formError ? (
              <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">{formError}</p>
            ) : null}
            <div className="grid gap-2">
              <Label htmlFor="category-name">Name</Label>
              <Input
                id="category-name"
                placeholder="e.g. Electric"
                value={form.name}
                onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
                aria-invalid={Boolean(fieldErrors.name)}
                disabled={isSaving}
              />
              {fieldErrors.name ? <p className="text-destructive text-sm">{fieldErrors.name}</p> : null}
            </div>
            <div className="grid gap-2">
              <Label htmlFor="category-code">Code</Label>
              <Input
                id="category-code"
                placeholder="e.g. ELECTRIC"
                value={form.code}
                onChange={(event) => setForm((current) => ({ ...current, code: event.target.value }))}
                aria-invalid={Boolean(fieldErrors.code)}
                disabled={isSaving}
              />
              {fieldErrors.code ? <p className="text-destructive text-sm">{fieldErrors.code}</p> : null}
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setDialogMode(null)} disabled={isSaving}>
                Cancel
              </Button>
              <Button type="submit" disabled={isSaving}>
                {isSaving ? <Loader2Icon className="animate-spin" /> : null}
                {dialogMode?.type === "edit" ? "Update category" : "Add category"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <AlertDialog open={deleteTarget !== null} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete category?</AlertDialogTitle>
            <AlertDialogDescription>
              This removes &ldquo;{deleteTarget?.name}&rdquo;. Deletion is blocked when existing sparepart records
              depend on this category.
            </AlertDialogDescription>
            {deleteError ? (
              <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">{deleteError}</p>
            ) : null}
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleteTaxonomy.isPending}>Cancel</AlertDialogCancel>
            <Button variant="destructive" onClick={confirmDelete} disabled={deleteTaxonomy.isPending}>
              {deleteTaxonomy.isPending ? <Loader2Icon className="animate-spin" /> : null}
              Delete category
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
  return (
    <div className="overflow-x-auto rounded-lg border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="whitespace-nowrap">Code</TableHead>
            <TableHead className="whitespace-nowrap">Name</TableHead>
            <TableHead className="whitespace-nowrap">Created</TableHead>
            <TableHead className="text-right w-32">Actions</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {items.map((entry) => (
            <TableRow key={entry.id ?? `${entry.code}-${entry.name}`}>
              <TableCell className="font-mono text-xs">{entry.code}</TableCell>
              <TableCell className="font-medium">{entry.name}</TableCell>
              <TableCell>{entry.createdAt ? formatDate(entry.createdAt) : "-"}</TableCell>
              <TableCell className="text-right">
                {canMutate ? (
                  <div className="flex justify-end gap-2">
                    <Button variant="outline" size="sm" onClick={() => onEdit(entry)}>
                      Edit
                    </Button>
                    <Button variant="destructive" size="sm" onClick={() => onDelete(entry)}>
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
