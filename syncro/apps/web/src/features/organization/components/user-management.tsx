"use client";

import { type FormEvent, useState } from "react";

import { Loader2Icon, TriangleAlertIcon } from "lucide-react";
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
import { useListJobTitles, useListUsersMaster, useUpdateUserMaster } from "@/features/organization/hooks/use-users";
import type { UserMasterView } from "@/features/organization/types";
import { SyncroApiError } from "@/lib/api/orval-mutator";
import { useAuthUser } from "@/lib/auth/use-auth-user";

type ErrorResponse = {
  code: string;
  message: string;
  fieldErrors?: Record<string, string>;
};

interface UserFormState {
  displayName: string;
  nik: string;
  phoneNumber: string;
  jobTitleId: string;
  departmentId: string;
}

const EMPTY_FORM: UserFormState = { displayName: "", nik: "", phoneNumber: "", jobTitleId: "", departmentId: "" };
const NONE = "__none__";

export function UserManagement() {
  const user = useAuthUser();
  const usersQuery = useListUsersMaster();
  const jobTitlesQuery = useListJobTitles();
  const updateUser = useUpdateUserMaster();

  const [editingUser, setEditingUser] = useState<UserMasterView | null>(null);
  const [form, setForm] = useState<UserFormState>(EMPTY_FORM);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);

  const canMutate = user?.applicationRole === "SUPER_ADMIN" || user?.applicationRole === "MANAGER_MAINTENANCE";
  const items = usersQuery.data?.data ?? [];
  const jobTitles = jobTitlesQuery.data?.data.items ?? [];

  function openEditDialog(target: UserMasterView) {
    setEditingUser(target);
    setForm({
      displayName: target.displayName ?? "",
      nik: target.nik ?? "",
      phoneNumber: target.phoneNumber ?? "",
      jobTitleId: target.jobTitleId ?? "",
      departmentId: target.departmentId ?? "",
    });
    setFieldErrors({});
    setFormError(null);
  }

  async function submitUser(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!editingUser) {
      return;
    }
    setFieldErrors({});
    setFormError(null);
    try {
      await updateUser.mutateAsync({
        userId: editingUser.id,
        data: {
          displayName: form.displayName.trim() === "" ? null : form.displayName.trim(),
          nik: form.nik.trim() === "" ? null : form.nik.trim(),
          phoneNumber: form.phoneNumber.trim() === "" ? null : form.phoneNumber.trim(),
          jobTitleId: form.jobTitleId === NONE || form.jobTitleId === "" ? null : form.jobTitleId,
          departmentId: form.departmentId === NONE || form.departmentId === "" ? null : form.departmentId,
        },
      });
      toast.success("User updated.");
      setEditingUser(null);
    } catch (error) {
      const response = errorResponse(error);
      if (response?.code === "DUPLICATE_IDENTIFIER") {
        toast.error("The NIK or phone number is already in use by another user.");
      } else {
        setFieldErrors(response?.fieldErrors ?? {});
        setFormError(response?.message ?? "User update failed.");
        toast.error(response?.message ?? "User update failed.");
      }
    }
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Users</CardTitle>
          <CardDescription>
            User master: display name, NIK, phone, job title, and department. NIK and phone are unique when set; empty
            values are cleared. Roles are managed at bootstrap — only master fields are editable here.
          </CardDescription>
          <CardAction>{canMutate ? null : <Badge variant="secondary">Read-only</Badge>}</CardAction>
        </CardHeader>
        <CardContent>
          {usersQuery.isLoading ? <UserTableSkeleton /> : null}
          {usersQuery.isError ? (
            <UserState
              title="Users could not be loaded"
              description="Refresh page or contact administrator if access should be available."
              action={
                <Button variant="outline" onClick={() => usersQuery.refetch()}>
                  Retry
                </Button>
              }
            />
          ) : null}
          {!usersQuery.isLoading && !usersQuery.isError && items.length === 0 ? (
            <UserState title="No users yet" description="Users are bootstrapped by the local admin." />
          ) : null}
          {items.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Login</TableHead>
                  <TableHead>NIK</TableHead>
                  <TableHead>Phone</TableHead>
                  <TableHead>Role</TableHead>
                  <TableHead>Job title</TableHead>
                  <TableHead>Status</TableHead>
                  {canMutate ? <TableHead className="text-right">Actions</TableHead> : null}
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((u) => (
                  <TableRow key={u.id}>
                    <TableCell className="font-medium">{u.displayName ?? u.loginIdentifier}</TableCell>
                    <TableCell>{u.loginIdentifier}</TableCell>
                    <TableCell>{u.nik ?? "—"}</TableCell>
                    <TableCell>{u.phoneNumber ?? "—"}</TableCell>
                    <TableCell>{u.applicationRole}</TableCell>
                    <TableCell>{jobTitleLabel(jobTitles, u.jobTitleId)}</TableCell>
                    <TableCell>
                      {u.enabled ? (
                        <Badge variant="outline">Enabled</Badge>
                      ) : (
                        <Badge variant="secondary">Disabled</Badge>
                      )}
                    </TableCell>
                    {canMutate ? (
                      <TableCell className="text-right">
                        <Button variant="outline" size="sm" onClick={() => openEditDialog(u)}>
                          Edit
                        </Button>
                      </TableCell>
                    ) : null}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : null}
        </CardContent>
      </Card>

      <Dialog open={editingUser !== null} onOpenChange={(open) => !open && setEditingUser(null)}>
        <DialogContent>
          <form onSubmit={submitUser} className="space-y-4">
            <DialogHeader>
              <DialogTitle>Edit user</DialogTitle>
              <DialogDescription>
                {editingUser?.loginIdentifier} — update master fields. Leave NIK/phone empty to clear.
              </DialogDescription>
            </DialogHeader>
            {formError ? (
              <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">{formError}</p>
            ) : null}
            <div className="grid gap-2">
              <Label htmlFor="user-display-name">Display name</Label>
              <Input
                id="user-display-name"
                value={form.displayName}
                onChange={(event) => setForm((c) => ({ ...c, displayName: event.target.value }))}
                aria-invalid={Boolean(fieldErrors.displayName)}
                disabled={updateUser.isPending}
              />
            </div>
            <div className="grid gap-2">
              <Label htmlFor="user-nik">NIK</Label>
              <Input
                id="user-nik"
                value={form.nik}
                onChange={(event) => setForm((c) => ({ ...c, nik: event.target.value }))}
                aria-invalid={Boolean(fieldErrors.nik)}
                disabled={updateUser.isPending}
              />
              {fieldErrors.nik ? <p className="text-destructive text-sm">{fieldErrors.nik}</p> : null}
            </div>
            <div className="grid gap-2">
              <Label htmlFor="user-phone">Phone number</Label>
              <Input
                id="user-phone"
                value={form.phoneNumber}
                onChange={(event) => setForm((c) => ({ ...c, phoneNumber: event.target.value }))}
                aria-invalid={Boolean(fieldErrors.phoneNumber)}
                disabled={updateUser.isPending}
              />
              {fieldErrors.phoneNumber ? <p className="text-destructive text-sm">{fieldErrors.phoneNumber}</p> : null}
            </div>
            <div className="grid gap-2">
              <Label htmlFor="user-job-title">Job title</Label>
              <Select
                value={form.jobTitleId || NONE}
                onValueChange={(value) => setForm((c) => ({ ...c, jobTitleId: value }))}
              >
                <SelectTrigger id="user-job-title" disabled={updateUser.isPending}>
                  <SelectValue placeholder="Select job title" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={NONE} className="italic text-muted-foreground">
                    None
                  </SelectItem>
                  {jobTitles.map((title) => (
                    <SelectItem key={title.id} value={title.id}>
                      {title.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setEditingUser(null)}
                disabled={updateUser.isPending}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={updateUser.isPending}>
                {updateUser.isPending ? <Loader2Icon className="animate-spin" /> : null}
                Save user
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function jobTitleLabel(jobTitles: Array<{ id: string; name: string }>, jobTitleId: string | null): string {
  if (!jobTitleId) {
    return "—";
  }
  const found = jobTitles.find((title) => title.id === jobTitleId);
  return found ? found.name : jobTitleId;
}

function UserState({ title, description, action }: { title: string; description: string; action?: React.ReactNode }) {
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

function UserTableSkeleton() {
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
