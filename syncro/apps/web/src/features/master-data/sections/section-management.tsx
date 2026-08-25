"use client";

import { type FormEvent, useMemo, useState } from "react";

import { useQueryClient } from "@tanstack/react-query";
import { Loader2Icon, PowerOff, TriangleAlertIcon } from "lucide-react";
import { toast } from "sonner";

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
import type { CreateSectionRequest, SectionView } from "@/lib/api/generated/model";
import {
  getListSectionsQueryKey,
  useCreateSection,
  useListPlants,
  useListSections,
  useUpdateSection,
} from "@/lib/api/generated/syncro";
import { SyncroApiError } from "@/lib/api/orval-mutator";
import { useAuthUser } from "@/lib/auth/use-auth-user";

const SECTION_CODES = ["MACHINERY", "UTILITY", "WORKSHOP"] as const;

type ErrorResponse = {
  code: string;
  message: string;
  fieldErrors?: Record<string, string>;
};

type DialogMode = { type: "create" | "edit"; section?: SectionView };

interface SectionFormState {
  plantId: string;
  code: string;
  name: string;
}

const EMPTY_FORM: SectionFormState = { plantId: "", code: "", name: "" };

export function SectionManagement() {
  const user = useAuthUser();
  const plantScope = usePlantScope();
  const scope = plantScope.scope;
  const activePlantId = plantScope.activePlantId;
  const queryClient = useQueryClient();
  const isAssignedEmpty = scope?.mode === "EMPTY";
  const [includeInactive, setIncludeInactive] = useState(true);

  const effectivePlantId = activePlantId !== "all" ? activePlantId : "";
  const sectionParams = {
    plantId: effectivePlantId || undefined,
    includeInactive,
  };
  const sectionsQuery = useListSections(sectionParams, {
    query: { enabled: Boolean(effectivePlantId) && !isAssignedEmpty },
  });
  const invalidateSections = () => queryClient.invalidateQueries({ queryKey: getListSectionsQueryKey() });
  const createSection = useCreateSection({ mutation: { onSuccess: invalidateSections } });
  const updateSection = useUpdateSection({ mutation: { onSuccess: invalidateSections } });

  const plants = useListPlants({ query: { enabled: Boolean(scope) && !isAssignedEmpty } });

  const [dialogMode, setDialogMode] = useState<DialogMode | null>(null);
  const [form, setForm] = useState<SectionFormState>(EMPTY_FORM);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);

  const canMutate = user?.applicationRole === "SUPER_ADMIN" || user?.applicationRole === "MANAGER_MAINTENANCE";
  const items = sectionsQuery.data?.data.items ?? [];
  const isSaving = createSection.isPending || updateSection.isPending;

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
    setForm({ ...EMPTY_FORM, plantId: effectivePlantId });
    setFieldErrors({});
    setFormError(null);
  }

  function openEditDialog(section: SectionView) {
    setDialogMode({ type: "edit", section });
    setForm({ plantId: section.plantId ?? "", code: section.code ?? "", name: section.name ?? "" });
    setFieldErrors({});
    setFormError(null);
  }

  async function submitSection(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFieldErrors({});
    setFormError(null);

    try {
      if (dialogMode?.type === "edit" && dialogMode.section) {
        await updateSection.mutateAsync({
          sectionId: dialogMode.section.id ?? "",
          data: { name: form.name.trim(), active: dialogMode.section.active ?? true },
        });
        toast.success("Section updated.");
      } else {
        const payload: CreateSectionRequest = {
          plantId: form.plantId,
          code: form.code as CreateSectionRequest["code"],
          name: form.name.trim(),
        };
        await createSection.mutateAsync({ data: payload });
        toast.success("Section created.");
      }
      setDialogMode(null);
    } catch (error) {
      const response = errorResponse(error);
      setFieldErrors(response?.fieldErrors ?? {});
      setFormError(response?.message ?? "Section request failed.");
      toast.error(response?.message ?? "Section request failed.");
    }
  }

  async function toggleActive(section: SectionView) {
    try {
      await updateSection.mutateAsync({
        sectionId: section.id ?? "",
        data: { name: section.name ?? "", active: !(section.active ?? true) },
      });
      toast.success(section.active ? "Section deactivated." : "Section reactivated.");
    } catch (error) {
      const response = errorResponse(error);
      if (response?.code === "SECTION_HAS_ACTIVE_MACHINE_GROUPS") {
        toast.error("Cannot deactivate: this section still has machine groups with active machines.");
      } else {
        toast.error(response?.message ?? "Section update failed.");
      }
    }
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Sections</CardTitle>
          <CardDescription>
            Org containers (MACHINERY / UTILITY / WORKSHOP) per plant. Machine groups are assigned to exactly one
            section.
          </CardDescription>
          <CardAction className="flex items-center gap-2">
            <Badge variant="outline">{scopeLabel}</Badge>
            {canMutate ? (
              <Button onClick={openCreateDialog}>Create section</Button>
            ) : (
              <Badge variant="secondary">Read-only</Badge>
            )}
          </CardAction>
        </CardHeader>
        <CardContent>
          {isAssignedEmpty ? (
            <SectionState title="No plant assignment" description="Your account has no assigned plant scope." />
          ) : null}
          {!effectivePlantId && !isAssignedEmpty ? (
            <SectionState
              title="Select a plant"
              description="Pick a specific plant in the plant switcher to manage its sections."
            />
          ) : null}
          {sectionsQuery.isLoading ? <SectionTableSkeleton /> : null}
          {sectionsQuery.isError ? (
            <SectionState
              title="Sections could not be loaded"
              description="Refresh page or contact administrator if access should be available."
              action={
                <Button variant="outline" onClick={() => sectionsQuery.refetch()}>
                  Retry
                </Button>
              }
            />
          ) : null}
          {effectivePlantId &&
          !sectionsQuery.isLoading &&
          !sectionsQuery.isError &&
          !isAssignedEmpty &&
          items.length === 0 ? (
            <SectionState title="No sections yet" description="Create the first section for this plant." />
          ) : null}
          {items.length > 0 ? (
            <>
              <label className="mb-2 flex items-center gap-2 text-muted-foreground text-xs">
                <input
                  type="checkbox"
                  checked={includeInactive}
                  onChange={(event) => setIncludeInactive(event.target.checked)}
                />
                Show inactive
              </label>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Code</TableHead>
                    <TableHead>Name</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead className="text-right">Actions</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {items.map((section) => (
                    <TableRow key={section.id}>
                      <TableCell className="font-medium">{section.code}</TableCell>
                      <TableCell>{section.name}</TableCell>
                      <TableCell>
                        {section.active ? (
                          <Badge variant="outline">Active</Badge>
                        ) : (
                          <Badge variant="secondary">Inactive</Badge>
                        )}
                      </TableCell>
                      <TableCell className="text-right">
                        {canMutate ? (
                          <div className="flex justify-end gap-2">
                            <Button variant="outline" size="sm" onClick={() => openEditDialog(section)}>
                              Edit
                            </Button>
                            <Button
                              variant={section.active ? "destructive" : "outline"}
                              size="sm"
                              disabled={updateSection.isPending}
                              onClick={() => void toggleActive(section)}
                            >
                              <PowerOff />
                              {section.active ? "Deactivate" : "Reactivate"}
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
            </>
          ) : null}
        </CardContent>
      </Card>

      <Dialog open={dialogMode !== null} onOpenChange={(open) => !open && setDialogMode(null)}>
        <DialogContent>
          <form onSubmit={submitSection} className="space-y-4">
            <DialogHeader>
              <DialogTitle>{dialogMode?.type === "edit" ? "Edit section" : "Create section"}</DialogTitle>
              <DialogDescription>
                Codes are MACHINERY, UTILITY, or WORKSHOP and must be unique per plant.
              </DialogDescription>
            </DialogHeader>
            {formError ? (
              <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">{formError}</p>
            ) : null}
            {dialogMode?.type === "create" ? (
              <>
                <div className="grid gap-2">
                  <Label htmlFor="section-plant">Plant</Label>
                  <Select value={form.plantId} onValueChange={(value) => setForm((c) => ({ ...c, plantId: value }))}>
                    <SelectTrigger id="section-plant" aria-invalid={Boolean(fieldErrors.plantId)} disabled={isSaving}>
                      <SelectValue placeholder="Select plant" />
                    </SelectTrigger>
                    <SelectContent>
                      {(plants.data?.data.items ?? []).map((plant) => (
                        <SelectItem key={plant.id} value={plant.id ?? ""}>
                          {plant.code} — {plant.name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  {fieldErrors.plantId ? <p className="text-destructive text-sm">{fieldErrors.plantId}</p> : null}
                </div>
                <div className="grid gap-2">
                  <Label htmlFor="section-code">Code</Label>
                  <Select value={form.code} onValueChange={(value) => setForm((c) => ({ ...c, code: value }))}>
                    <SelectTrigger id="section-code" aria-invalid={Boolean(fieldErrors.code)} disabled={isSaving}>
                      <SelectValue placeholder="Select code" />
                    </SelectTrigger>
                    <SelectContent>
                      {SECTION_CODES.map((code) => (
                        <SelectItem key={code} value={code}>
                          {code}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  {fieldErrors.code ? <p className="text-destructive text-sm">{fieldErrors.code}</p> : null}
                </div>
              </>
            ) : null}
            <div className="grid gap-2">
              <Label htmlFor="section-name">Name</Label>
              <Input
                id="section-name"
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
                Save section
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function SectionState({
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

function SectionTableSkeleton() {
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
