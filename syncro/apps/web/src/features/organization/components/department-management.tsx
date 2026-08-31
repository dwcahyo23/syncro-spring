"use client";

import { type FormEvent, useMemo, useState } from "react";

import { Loader2Icon, PowerOff, TriangleAlertIcon, Users } from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
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
import {
  useCreateDepartment,
  useDeleteDepartment,
  useListDepartments,
  useSetDepartmentMembers,
  useUpdateDepartment,
} from "@/features/organization/hooks/use-departments";
import { useListUsersMaster } from "@/features/organization/hooks/use-users";
import type { DepartmentView, UserMasterView } from "@/features/organization/types";
import { usePlantScope } from "@/features/plant-scope/plant-scope-store";
import { useListPlants } from "@/lib/api/generated/syncro";
import { SyncroApiError } from "@/lib/api/orval-mutator";
import { useAuthUser } from "@/lib/auth/use-auth-user";

type ErrorResponse = {
  code: string;
  message: string;
  fieldErrors?: Record<string, string>;
};

type DialogMode = { type: "create" | "edit"; department?: DepartmentView };

interface DepartmentFormState {
  plantId: string;
  name: string;
  spvId: string;
  mgId: string;
}

const EMPTY_FORM: DepartmentFormState = { plantId: "", name: "", spvId: "", mgId: "" };
const NONE = "__none__";

export function DepartmentManagement() {
  const user = useAuthUser();
  const plantScope = usePlantScope();
  const scope = plantScope.scope;
  const activePlantId = plantScope.activePlantId;
  const isAssignedEmpty = scope?.mode === "EMPTY";

  const [includeInactive, setIncludeInactive] = useState(true);
  const [dialogMode, setDialogMode] = useState<DialogMode | null>(null);
  const [form, setForm] = useState<DepartmentFormState>(EMPTY_FORM);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [memberDialog, setMemberDialog] = useState<DepartmentView | null>(null);
  const [selectedUserIds, setSelectedUserIds] = useState<string[]>([]);

  const effectivePlantId = activePlantId !== "all" ? activePlantId : "";

  const departmentsQuery = useListDepartments(effectivePlantId || undefined, includeInactive);
  const createDepartment = useCreateDepartment();
  const updateDepartment = useUpdateDepartment();
  const deleteDepartment = useDeleteDepartment();
  const setMembers = useSetDepartmentMembers();
  const usersQuery = useListUsersMaster();
  const plants = useListPlants({ query: { enabled: Boolean(scope) && !isAssignedEmpty } });

  const activeUsers = useMemo(() => {
    const users = usersQuery.data?.data ?? [];
    return users.filter((u) => u.enabled);
  }, [usersQuery.data]);

  const userById = useMemo(() => {
    const users = usersQuery.data?.data ?? [];
    return new Map(users.map((u) => [u.id, u]));
  }, [usersQuery.data]);

  const canMutate = user?.applicationRole === "SUPER_ADMIN" || user?.applicationRole === "MANAGER_MAINTENANCE";
  const items = departmentsQuery.data?.data?.items ?? [];
  const isSaving = createDepartment.isPending || updateDepartment.isPending || deleteDepartment.isPending;

  function openCreateDialog() {
    setDialogMode({ type: "create" });
    setForm({ ...EMPTY_FORM, plantId: effectivePlantId });
    setFieldErrors({});
    setFormError(null);
  }

  function openEditDialog(department: DepartmentView) {
    setDialogMode({ type: "edit", department });
    setForm({
      plantId: department.plantId,
      name: department.name,
      spvId: department.spvId ?? "",
      mgId: department.mgId ?? "",
    });
    setFieldErrors({});
    setFormError(null);
  }

  function openMembersDialog(department: DepartmentView) {
    setMemberDialog(department);
    // Pre-select the current member set from the department memberCount is not
    // sufficient — the list endpoint returns only counts. The hierarchy read is
    // the source for member ids; for the member picker we start empty and the
    // full-replace semantics mean saving overwrites the set.
    setSelectedUserIds([]);
  }

  async function submitDepartment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFieldErrors({});
    setFormError(null);

    try {
      if (dialogMode?.type === "edit" && dialogMode.department) {
        await updateDepartment.mutateAsync({
          departmentId: dialogMode.department.id,
          data: {
            name: form.name.trim(),
            spvId: form.spvId === NONE || form.spvId === "" ? null : form.spvId,
            mgId: form.mgId === NONE || form.mgId === "" ? null : form.mgId,
            active: dialogMode.department.active,
          },
        });
        toast.success("Department updated.");
      } else {
        await createDepartment.mutateAsync({
          plantId: form.plantId,
          name: form.name.trim(),
          spvId: form.spvId === NONE || form.spvId === "" ? null : form.spvId,
          mgId: form.mgId === NONE || form.mgId === "" ? null : form.mgId,
        });
        toast.success("Department created.");
      }
      setDialogMode(null);
    } catch (error) {
      const response = errorResponse(error);
      setFieldErrors(response?.fieldErrors ?? {});
      setFormError(response?.message ?? "Department request failed.");
      toast.error(response?.message ?? "Department request failed.");
    }
  }

  async function deactivateDepartment(department: DepartmentView) {
    try {
      await deleteDepartment.mutateAsync(department.id);
      toast.success("Department deactivated.");
    } catch (error) {
      const response = errorResponse(error);
      if (response?.code === "DEPARTMENT_HAS_MEMBERS") {
        toast.error("Cannot deactivate: this department still has members.");
      } else {
        toast.error(response?.message ?? "Department deactivation failed.");
      }
    }
  }

  async function saveMembers() {
    if (!memberDialog) {
      return;
    }
    try {
      await setMembers.mutateAsync({ departmentId: memberDialog.id, data: { userIds: selectedUserIds } });
      toast.success("Department members updated.");
      setMemberDialog(null);
    } catch (error) {
      const response = errorResponse(error);
      toast.error(response?.message ?? "Member update failed.");
    }
  }

  const toggleUserSelection = (userId: string) => {
    setSelectedUserIds((prev) => (prev.includes(userId) ? prev.filter((id) => id !== userId) : [...prev, userId]));
  };

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Departments</CardTitle>
          <CardDescription>
            Organization-maintenance departments (people units) per plant, with SPV/MG leaders and member technicians.
            Departments are soft-inactivated, never deleted.
          </CardDescription>
          <CardAction className="flex items-center gap-2">
            {canMutate ? (
              <Button onClick={openCreateDialog}>Create department</Button>
            ) : (
              <Badge variant="secondary">Read-only</Badge>
            )}
          </CardAction>
        </CardHeader>
        <CardContent>
          {isAssignedEmpty ? (
            <DepartmentState title="No plant assignment" description="Your account has no assigned plant scope." />
          ) : null}
          {!effectivePlantId && !isAssignedEmpty ? (
            <DepartmentState
              title="Select a plant"
              description="Pick a specific plant in the plant switcher to manage its departments."
            />
          ) : null}
          {departmentsQuery.isLoading ? <DepartmentTableSkeleton /> : null}
          {departmentsQuery.isError ? (
            <DepartmentState
              title="Departments could not be loaded"
              description="Refresh page or contact administrator if access should be available."
              action={
                <Button variant="outline" onClick={() => departmentsQuery.refetch()}>
                  Retry
                </Button>
              }
            />
          ) : null}
          {effectivePlantId &&
          !departmentsQuery.isLoading &&
          !departmentsQuery.isError &&
          !isAssignedEmpty &&
          items.length === 0 ? (
            <DepartmentState title="No departments yet" description="Create the first department for this plant." />
          ) : null}
          {items.length > 0 ? (
            <>
              <div className="mb-2 flex items-center gap-2 text-muted-foreground text-xs">
                <Checkbox
                  id="show-inactive-departments"
                  checked={includeInactive}
                  onCheckedChange={(checked) => setIncludeInactive(checked === true)}
                />
                <label htmlFor="show-inactive-departments">Show inactive</label>
              </div>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Name</TableHead>
                    <TableHead>SPV</TableHead>
                    <TableHead>MG</TableHead>
                    <TableHead>Members</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead className="text-right">Actions</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {items.map((department) => (
                    <TableRow key={department.id}>
                      <TableCell className="font-medium">{department.name}</TableCell>
                      <TableCell>{leaderLabel(userById, department.spvId)}</TableCell>
                      <TableCell>{leaderLabel(userById, department.mgId)}</TableCell>
                      <TableCell>{department.memberCount}</TableCell>
                      <TableCell>
                        {department.active ? (
                          <Badge variant="outline">Active</Badge>
                        ) : (
                          <Badge variant="secondary">Inactive</Badge>
                        )}
                      </TableCell>
                      <TableCell className="text-right">
                        {canMutate ? (
                          <div className="flex justify-end gap-2">
                            <Button
                              variant="outline"
                              size="sm"
                              onClick={() => openMembersDialog(department)}
                              disabled={!department.active}
                            >
                              <Users />
                              Members
                            </Button>
                            <Button variant="outline" size="sm" onClick={() => openEditDialog(department)}>
                              Edit
                            </Button>
                            {department.active ? (
                              <Button
                                variant="outline"
                                size="sm"
                                disabled={deleteDepartment.isPending}
                                onClick={() => void deactivateDepartment(department)}
                              >
                                <PowerOff />
                                Deactivate
                              </Button>
                            ) : null}
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

      {/* Create / edit dialog */}
      <Dialog open={dialogMode !== null} onOpenChange={(open) => !open && setDialogMode(null)}>
        <DialogContent className="top-4 max-h-[calc(100svh-2rem)] translate-y-0 overflow-y-auto sm:max-w-2xl">
          <form onSubmit={submitDepartment} className="space-y-4">
            <DialogHeader>
              <DialogTitle>{dialogMode?.type === "edit" ? "Edit department" : "Create department"}</DialogTitle>
              <DialogDescription>
                SPV/MG must be enabled users with access to the same plant. Names must be unique per plant.
              </DialogDescription>
            </DialogHeader>
            {formError ? (
              <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">{formError}</p>
            ) : null}
            <div className="grid gap-2">
              <Label htmlFor="department-plant">Plant</Label>
              <Select
                value={form.plantId}
                onValueChange={(value) => setForm((c) => ({ ...c, plantId: value }))}
                disabled={dialogMode?.type === "edit" || isSaving}
              >
                <SelectTrigger id="department-plant" aria-invalid={Boolean(fieldErrors.plantId)}>
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
              <Label htmlFor="department-name">Name</Label>
              <Input
                id="department-name"
                value={form.name}
                onChange={(event) => setForm((c) => ({ ...c, name: event.target.value }))}
                aria-invalid={Boolean(fieldErrors.name)}
                disabled={isSaving}
              />
              {fieldErrors.name ? <p className="text-destructive text-sm">{fieldErrors.name}</p> : null}
            </div>
            <div className="grid gap-2">
              <Label htmlFor="department-spv">SPV</Label>
              <Select value={form.spvId || NONE} onValueChange={(value) => setForm((c) => ({ ...c, spvId: value }))}>
                <SelectTrigger id="department-spv" aria-invalid={Boolean(fieldErrors.spvId)} disabled={isSaving}>
                  <SelectValue placeholder="Select SPV" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={NONE} className="italic text-muted-foreground">
                    Unassigned
                  </SelectItem>
                  {activeUsers.map((u) => (
                    <SelectItem key={u.id} value={u.id}>
                      {userLabel(u)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {fieldErrors.spvId ? <p className="text-destructive text-sm">{fieldErrors.spvId}</p> : null}
            </div>
            <div className="grid gap-2">
              <Label htmlFor="department-mg">MG</Label>
              <Select value={form.mgId || NONE} onValueChange={(value) => setForm((c) => ({ ...c, mgId: value }))}>
                <SelectTrigger id="department-mg" aria-invalid={Boolean(fieldErrors.mgId)} disabled={isSaving}>
                  <SelectValue placeholder="Select MG" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={NONE} className="italic text-muted-foreground">
                    Unassigned
                  </SelectItem>
                  {activeUsers.map((u) => (
                    <SelectItem key={u.id} value={u.id}>
                      {userLabel(u)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {fieldErrors.mgId ? <p className="text-destructive text-sm">{fieldErrors.mgId}</p> : null}
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setDialogMode(null)} disabled={isSaving}>
                Cancel
              </Button>
              <Button type="submit" disabled={isSaving}>
                {isSaving ? <Loader2Icon className="animate-spin" /> : null}
                Save department
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Member binding dialog */}
      <Dialog open={memberDialog !== null} onOpenChange={(open) => !open && setMemberDialog(null)}>
        <DialogContent className="top-4 max-h-[calc(100svh-2rem)] translate-y-0 overflow-y-auto sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>Manage members — {memberDialog?.name}</DialogTitle>
            <DialogDescription>
              Assign staff to this department. Members can belong to multiple departments; saving replaces the member
              set.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            {activeUsers.length === 0 ? (
              <p className="text-muted-foreground text-sm">No enabled users available.</p>
            ) : (
              activeUsers.map((u) => {
                const isSelected = selectedUserIds.includes(u.id);
                return (
                  <label
                    key={u.id}
                    htmlFor={`dept-member-${u.id}`}
                    className={`flex cursor-pointer items-center gap-3 rounded-md border p-2 transition-colors hover:bg-muted/50 ${
                      isSelected ? "border-primary/50 bg-primary/5" : ""
                    }`}
                  >
                    <Checkbox
                      id={`dept-member-${u.id}`}
                      className="mr-3"
                      checked={isSelected}
                      onCheckedChange={() => toggleUserSelection(u.id)}
                    />
                    <span className="text-sm">
                      <span className="block font-medium">{u.displayName ?? u.loginIdentifier}</span>
                      <span className="block text-muted-foreground text-xs">{u.nik ?? u.loginIdentifier}</span>
                    </span>
                  </label>
                );
              })
            )}
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setMemberDialog(null)}>
              Cancel
            </Button>
            <Button type="button" onClick={() => void saveMembers()} disabled={setMembers.isPending}>
              {setMembers.isPending ? <Loader2Icon className="animate-spin" /> : null}
              Save assignments
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function leaderLabel(users: Map<string, UserMasterView>, userId: string | null): React.ReactNode {
  if (!userId) {
    return <span className="text-muted-foreground text-xs italic">Unassigned</span>;
  }
  const user = users.get(userId);
  return user ? (user.displayName ?? user.loginIdentifier) : userId;
}

function userLabel(user: UserMasterView): string {
  return `${user.displayName ?? user.loginIdentifier} (${user.nik ?? user.loginIdentifier})`;
}

function DepartmentState({
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

function DepartmentTableSkeleton() {
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
