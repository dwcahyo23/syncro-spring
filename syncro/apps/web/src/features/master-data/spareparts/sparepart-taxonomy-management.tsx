"use client";

import { type FormEvent, useMemo, useState } from "react";

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

type TaxonomyDimension = SparepartTaxonomyRequest["dimension"];
type TaxonomyFormState = SparepartTaxonomyRequest;
type DialogMode =
  | { type: "create"; dimension: TaxonomyDimension; entry?: never }
  | { type: "edit"; entry: SparepartTaxonomyView; dimension?: never };
type ErrorResponse = { code: string; message: string; fieldErrors?: Record<string, string> };

const DIMENSIONS = [
  {
    value: SparepartTaxonomyRequestDimension.CATEGORY,
    label: "Category",
    description: "High-level sparepart families.",
  },
  { value: SparepartTaxonomyRequestDimension.BRAND, label: "Brand", description: "Manufacturers or vendor brands." },
  { value: SparepartTaxonomyRequestDimension.KIND, label: "Kind", description: "Functional sparepart kinds." },
  { value: SparepartTaxonomyRequestDimension.TYPE, label: "Type", description: "Specific sparepart types." },
] as const;

export function SparepartTaxonomyManagement() {
  const user = useAuthUser();
  const queryClient = useQueryClient();
  const canMutate = user?.applicationRole === "SUPER_ADMIN" || user?.applicationRole === "MANAGE";
  const taxonomy = useListSparepartTaxonomies();
  const createTaxonomy = useCreateSparepartTaxonomy({ mutation: { onSuccess: invalidateTaxonomyData } });
  const updateTaxonomy = useUpdateSparepartTaxonomy({ mutation: { onSuccess: invalidateTaxonomyData } });
  const deleteTaxonomy = useDeleteSparepartTaxonomy({ mutation: { onSuccess: invalidateTaxonomyData } });
  const [dialogMode, setDialogMode] = useState<DialogMode | null>(null);
  const [form, setForm] = useState<TaxonomyFormState>({
    dimension: SparepartTaxonomyRequestDimension.CATEGORY,
    code: "",
    name: "",
  });
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<SparepartTaxonomyView | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const items = taxonomy.data?.data.items ?? [];
  const groupedItems = useMemo(() => groupByDimension(items), [items]);
  const isSaving = createTaxonomy.isPending || updateTaxonomy.isPending;

  function invalidateTaxonomyData() {
    queryClient.invalidateQueries({ queryKey: getListSparepartTaxonomiesQueryKey() });
  }

  function openCreateDialog(dimension: TaxonomyDimension) {
    setDialogMode({ type: "create", dimension });
    setForm({ dimension, code: "", name: "" });
    setFieldErrors({});
    setFormError(null);
  }

  function openEditDialog(entry: SparepartTaxonomyView) {
    setDialogMode({ type: "edit", entry });
    setForm({
      dimension: entry.dimension ?? SparepartTaxonomyRequestDimension.CATEGORY,
      code: entry.code ?? "",
      name: entry.name ?? "",
    });
    setFieldErrors({});
    setFormError(null);
  }

  async function submitTaxonomy(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFieldErrors({});
    setFormError(null);

    try {
      if (dialogMode?.type === "edit") {
        await updateTaxonomy.mutateAsync({ taxonomyId: dialogMode.entry.id ?? "", data: form });
        toast.success("Taxonomy entry updated.");
      } else {
        await createTaxonomy.mutateAsync({ data: form });
        toast.success("Taxonomy entry created.");
      }
      setDialogMode(null);
    } catch (error) {
      const response = errorResponse(error);
      setFieldErrors(response?.fieldErrors ?? {});
      setFormError(response?.message ?? "Taxonomy request failed.");
      toast.error(response?.message ?? "Taxonomy request failed.");
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) {
      return;
    }
    setDeleteError(null);

    try {
      await deleteTaxonomy.mutateAsync({ taxonomyId: deleteTarget.id ?? "" });
      toast.success("Taxonomy entry deleted.");
      setDeleteTarget(null);
    } catch (error) {
      const message = errorResponse(error)?.message ?? "Taxonomy delete failed.";
      setDeleteError(message);
      toast.error(message);
    }
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Sparepart Taxonomy</CardTitle>
          <CardDescription>Manage global category, brand, kind, and type reference values.</CardDescription>
          <CardAction>{canMutate ? null : <Badge variant="secondary">Read-only</Badge>}</CardAction>
        </CardHeader>
        <CardContent className="space-y-4">
          {taxonomy.isLoading ? <TaxonomySkeleton /> : null}
          {taxonomy.isError ? (
            <TaxonomyState
              title="Taxonomy could not be loaded"
              description="Refresh page or contact administrator if access should be available."
              action={
                <Button variant="outline" onClick={() => taxonomy.refetch()}>
                  Retry
                </Button>
              }
            />
          ) : null}
          {!taxonomy.isLoading && !taxonomy.isError && items.length === 0 ? (
            <TaxonomyState title="No taxonomy entries yet" description="Create first sparepart taxonomy entry." />
          ) : null}
          {!taxonomy.isLoading && !taxonomy.isError ? (
            <div className="grid gap-4 lg:grid-cols-2">
              {DIMENSIONS.map((dimension) => (
                <Card key={dimension.value}>
                  <CardHeader>
                    <CardTitle>{dimension.label}</CardTitle>
                    <CardDescription>{dimension.description}</CardDescription>
                    <CardAction>
                      {canMutate ? (
                        <Button size="sm" onClick={() => openCreateDialog(dimension.value)}>
                          Add {dimension.label.toLowerCase()}
                        </Button>
                      ) : null}
                    </CardAction>
                  </CardHeader>
                  <CardContent>
                    <TaxonomyTable
                      items={groupedItems.get(dimension.value) ?? []}
                      canMutate={canMutate}
                      onEdit={openEditDialog}
                      onDelete={(entry) => {
                        setDeleteError(null);
                        setDeleteTarget(entry);
                      }}
                    />
                  </CardContent>
                </Card>
              ))}
            </div>
          ) : null}
        </CardContent>
      </Card>

      <Dialog open={dialogMode !== null} onOpenChange={(open) => !open && setDialogMode(null)}>
        <DialogContent>
          <form onSubmit={submitTaxonomy} className="space-y-4">
            <DialogHeader>
              <DialogTitle>{dialogMode?.type === "edit" ? "Edit taxonomy entry" : "Create taxonomy entry"}</DialogTitle>
              <DialogDescription>
                Names must be unique within the same dimension and may repeat across dimensions.
              </DialogDescription>
            </DialogHeader>
            {formError ? (
              <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">{formError}</p>
            ) : null}
            <div className="grid gap-2">
              <Label htmlFor="taxonomy-dimension">Dimension</Label>
              <TaxonomyDimensionSelect
                value={form.dimension}
                disabled={isSaving || dialogMode?.type === "edit"}
                onChange={(dimension) => setForm((current) => ({ ...current, dimension }))}
              />
              {fieldErrors.dimension ? <p className="text-destructive text-sm">{fieldErrors.dimension}</p> : null}
            </div>
            <div className="grid gap-2">
              <Label htmlFor="taxonomy-name">Name</Label>
              <Input
                id="taxonomy-name"
                value={form.name}
                onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
                aria-invalid={Boolean(fieldErrors.name)}
                disabled={isSaving}
              />
              {fieldErrors.name ? <p className="text-destructive text-sm">{fieldErrors.name}</p> : null}
            </div>
            <div className="grid gap-2">
              <Label htmlFor="taxonomy-code">Code</Label>
              <Input
                id="taxonomy-code"
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
                Save taxonomy entry
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <AlertDialog open={deleteTarget !== null} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete taxonomy entry?</AlertDialogTitle>
            <AlertDialogDescription>
              This removes {deleteTarget?.name}. Deletion is blocked when existing sparepart records depend on it.
            </AlertDialogDescription>
            {deleteError ? (
              <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">{deleteError}</p>
            ) : null}
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleteTaxonomy.isPending}>Cancel</AlertDialogCancel>
            <Button variant="destructive" onClick={confirmDelete} disabled={deleteTaxonomy.isPending}>
              {deleteTaxonomy.isPending ? <Loader2Icon className="animate-spin" /> : null}
              Delete taxonomy entry
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

function TaxonomyTable({
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
  if (items.length === 0) {
    return <TaxonomyState title="No entries" description="No values exist for this dimension yet." />;
  }

  return (
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
        {items.map((entry) => (
          <TableRow key={entry.id ?? `${entry.dimension}-${entry.code}-${entry.name}`}>
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
  );
}

function TaxonomyDimensionSelect({
  value,
  disabled,
  onChange,
}: {
  value: TaxonomyDimension;
  disabled?: boolean;
  onChange: (value: TaxonomyDimension) => void;
}) {
  return (
    <Select value={value} onValueChange={onChange} disabled={disabled}>
      <SelectTrigger id="taxonomy-dimension">
        <SelectValue placeholder="Select dimension" />
      </SelectTrigger>
      <SelectContent>
        {DIMENSIONS.map((dimension) => (
          <SelectItem key={dimension.value} value={dimension.value}>
            {dimension.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

function TaxonomyState({
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

function TaxonomySkeleton() {
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      {DIMENSIONS.map((dimension) => (
        <Card key={dimension.value}>
          <CardHeader>
            <Skeleton className="h-5 w-28" />
            <Skeleton className="h-4 w-48" />
          </CardHeader>
          <CardContent className="space-y-2">
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-full" />
          </CardContent>
        </Card>
      ))}
    </div>
  );
}

function groupByDimension(items: SparepartTaxonomyView[]) {
  return items.reduce((groups, item) => {
    if (item.dimension) {
      groups.set(item.dimension, [...(groups.get(item.dimension) ?? []), item]);
    }
    return groups;
  }, new Map<TaxonomyDimension, SparepartTaxonomyView[]>());
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
