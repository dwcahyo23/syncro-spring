"use client";

import { type FormEvent, useState } from "react";

import { Loader2Icon, TriangleAlertIcon } from "lucide-react";
import { useTranslations } from "next-intl";
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
import { apiErrorMessage, errorResponse } from "@/lib/api/error-response";
import { useAuthUser } from "@/lib/auth/use-auth-user";

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
  const t = useTranslations("organization");
  const tc = useTranslations("common");
  const te = useTranslations("errors");
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
      toast.success(t("users.updated"));
      setEditingUser(null);
    } catch (error) {
      const response = errorResponse(error);
      if (response?.code === "DUPLICATE_IDENTIFIER") {
        toast.error(te("DUPLICATE_IDENTIFIER"));
      } else {
        setFieldErrors(response?.fieldErrors ?? {});
        const message = response ? apiErrorMessage(te, response) : t("users.updateFailed");
        setFormError(message);
        toast.error(message);
      }
    }
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>{t("users.title")}</CardTitle>
          <CardDescription>{t("users.description")}</CardDescription>
          <CardAction>{canMutate ? null : <Badge variant="secondary">{t("readOnly")}</Badge>}</CardAction>
        </CardHeader>
        <CardContent>
          {usersQuery.isLoading ? <UserTableSkeleton /> : null}
          {usersQuery.isError ? (
            <UserState
              title={t("users.couldNotLoadTitle")}
              description={t("users.couldNotLoadDesc")}
              action={
                <Button variant="outline" onClick={() => usersQuery.refetch()}>
                  {tc("retry")}
                </Button>
              }
            />
          ) : null}
          {!usersQuery.isLoading && !usersQuery.isError && items.length === 0 ? (
            <UserState title={t("users.noUsersTitle")} description={t("users.noUsersDesc")} />
          ) : null}
          {items.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tc("name")}</TableHead>
                  <TableHead>{t("users.login")}</TableHead>
                  <TableHead>{t("users.nik")}</TableHead>
                  <TableHead>{t("users.phone")}</TableHead>
                  <TableHead>{t("role")}</TableHead>
                  <TableHead>{t("jobTitle")}</TableHead>
                  <TableHead>{tc("status")}</TableHead>
                  {canMutate ? <TableHead className="text-right">{tc("actions")}</TableHead> : null}
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((u) => (
                  <TableRow key={u.id}>
                    <TableCell className="font-medium">{u.displayName ?? u.loginIdentifier}</TableCell>
                    <TableCell>{u.loginIdentifier}</TableCell>
                    <TableCell>{u.nik ?? tc("notAvailable")}</TableCell>
                    <TableCell>{u.phoneNumber ?? tc("notAvailable")}</TableCell>
                    <TableCell>{u.applicationRole}</TableCell>
                    <TableCell>{jobTitleLabel(jobTitles, u.jobTitleId, tc("notAvailable"))}</TableCell>
                    <TableCell>
                      {u.enabled ? (
                        <Badge variant="outline">{t("users.enabled")}</Badge>
                      ) : (
                        <Badge variant="secondary">{t("users.disabled")}</Badge>
                      )}
                    </TableCell>
                    {canMutate ? (
                      <TableCell className="text-right">
                        <Button variant="outline" size="sm" onClick={() => openEditDialog(u)}>
                          {tc("edit")}
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
        <DialogContent className="top-4 max-h-[calc(100svh-2rem)] translate-y-0 overflow-y-auto sm:max-w-2xl">
          <form onSubmit={submitUser} className="space-y-4">
            <DialogHeader>
              <DialogTitle>{t("users.editUser")}</DialogTitle>
              <DialogDescription>
                {t("users.editUserHint", { login: editingUser?.loginIdentifier ?? "" })}
              </DialogDescription>
            </DialogHeader>
            {formError ? (
              <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">{formError}</p>
            ) : null}
            <div className="grid gap-2">
              <Label htmlFor="user-display-name">{t("users.displayName")}</Label>
              <Input
                id="user-display-name"
                value={form.displayName}
                onChange={(event) => setForm((c) => ({ ...c, displayName: event.target.value }))}
                aria-invalid={Boolean(fieldErrors.displayName)}
                disabled={updateUser.isPending}
              />
            </div>
            <div className="grid gap-2">
              <Label htmlFor="user-nik">{t("users.nik")}</Label>
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
              <Label htmlFor="user-phone">{t("users.phoneNumber")}</Label>
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
              <Label htmlFor="user-job-title">{t("jobTitle")}</Label>
              <Select
                value={form.jobTitleId || NONE}
                onValueChange={(value) => setForm((c) => ({ ...c, jobTitleId: value }))}
              >
                <SelectTrigger id="user-job-title" disabled={updateUser.isPending}>
                  <SelectValue placeholder={t("selectJobTitle")} />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={NONE} className="italic text-muted-foreground">
                    {tc("none")}
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
                {tc("cancel")}
              </Button>
              <Button type="submit" disabled={updateUser.isPending}>
                {updateUser.isPending ? <Loader2Icon className="animate-spin" /> : null}
                {t("users.saveUser")}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function jobTitleLabel(
  jobTitles: Array<{ id: string; name: string }>,
  jobTitleId: string | null,
  notAvailable: string,
): string {
  if (!jobTitleId) {
    return notAvailable;
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
