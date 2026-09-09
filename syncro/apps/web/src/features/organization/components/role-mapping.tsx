"use client";

import { type FormEvent, useMemo, useState } from "react";

import { Loader2Icon, TriangleAlertIcon, XIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import {
  useAddUserRole,
  useGetUserBindings,
  useListSystemRoles,
  useRemoveUserRole,
  useSetUserJob,
} from "@/features/organization/hooks/use-user-bindings";
import { useListJobTitles, useListUsersMaster } from "@/features/organization/hooks/use-users";
import type { UserMasterView } from "@/features/organization/types";
import { apiErrorMessage, errorResponse } from "@/lib/api/error-response";
import { useAuthUser } from "@/lib/auth/use-auth-user";

const NONE = "__none__";

export function RoleMapping() {
  const t = useTranslations("organization");
  const tc = useTranslations("common");
  const te = useTranslations("errors");
  const authUser = useAuthUser();
  const canMutate = authUser?.applicationRole === "SUPER_ADMIN" || authUser?.applicationRole === "MANAGER_MAINTENANCE";
  const usersQuery = useListUsersMaster();
  const jobTitlesQuery = useListJobTitles();
  const systemRolesQuery = useListSystemRoles();
  const userItems = usersQuery.data?.data ?? [];
  const jobTitleItems = jobTitlesQuery.data?.data.items ?? [];
  const systemRoleItems = systemRolesQuery.data?.data.items ?? [];

  const [selectedUserId, setSelectedUserId] = useState<string | null>(null);
  const bindings = useGetUserBindings(selectedUserId ?? undefined);
  const setJob = useSetUserJob();
  const addRole = useAddUserRole();
  const removeRole = useRemoveUserRole();
  const [pendingJobTitleId, setPendingJobTitleId] = useState(NONE);
  const [pendingRoleId, setPendingRoleId] = useState(NONE);
  const [pendingOverride, setPendingOverride] = useState(false);

  const selectedUser = useMemo(() => userItems.find((u) => u.id === selectedUserId), [userItems, selectedUserId]);

  function selectUser(userId: string) {
    setSelectedUserId(userId);
    setPendingJobTitleId(NONE);
    setPendingRoleId(NONE);
    setPendingOverride(false);
  }

  async function submitJob(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedUserId) return;
    try {
      const jobTitleId = pendingJobTitleId === NONE ? null : pendingJobTitleId;
      await setJob.mutateAsync({ userId: selectedUserId, data: { jobTitleId } });
      toast.success(t("roles.jobUpdated"));
    } catch (error) {
      const response = errorResponse(error);
      toast.error(response ? apiErrorMessage(te, response) : t("roles.jobUpdateFailed"));
    }
  }

  async function submitAddRole() {
    if (!selectedUserId || pendingRoleId === NONE) return;
    try {
      await addRole.mutateAsync({
        userId: selectedUserId,
        data: { systemRoleId: pendingRoleId, isOverride: pendingOverride },
      });
      toast.success(t("roles.roleAdded"));
      setPendingRoleId(NONE);
      setPendingOverride(false);
    } catch (error) {
      const response = errorResponse(error);
      toast.error(response ? apiErrorMessage(te, response) : t("roles.roleAddFailed"));
    }
  }

  async function handleRemoveRole(bindingId: string) {
    if (!selectedUserId) return;
    try {
      await removeRole.mutateAsync({ userId: selectedUserId, bindingId });
      toast.success(t("roles.roleRemoved"));
    } catch (error) {
      const response = errorResponse(error);
      toast.error(response ? apiErrorMessage(te, response) : t("roles.roleRemoveFailed"));
    }
  }

  const isSaving = setJob.isPending || addRole.isPending || removeRole.isPending;

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>{t("roles.title")}</CardTitle>
          <CardDescription>{t("roles.description")}</CardDescription>
          <CardAction>{canMutate ? null : <Badge variant="secondary">{t("readOnly")}</Badge>}</CardAction>
        </CardHeader>
        <CardContent>
          {usersQuery.isLoading ? <TableSkeleton /> : null}
          {usersQuery.isError ? (
            <State
              title={t("users.couldNotLoadTitle")}
              description={t("roles.loadFailedDesc")}
              action={
                <Button variant="outline" onClick={() => usersQuery.refetch()}>
                  {tc("retry")}
                </Button>
              }
            />
          ) : null}
          {!usersQuery.isLoading && !usersQuery.isError && userItems.length === 0 ? (
            <State title={t("users.noUsersTitle")} description={t("users.noUsersDesc")} />
          ) : null}
          {userItems.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tc("name")}</TableHead>
                  <TableHead>{t("role")}</TableHead>
                  <TableHead>{t("jobTitle")}</TableHead>
                  <TableHead>{t("roles.systemRoles")}</TableHead>
                  <TableHead className="text-right">{tc("actions")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {userItems.map((u) => (
                  <TableRow key={u.id} className={selectedUserId === u.id ? "bg-accent" : undefined}>
                    <TableCell className="font-medium">{u.displayName ?? u.loginIdentifier}</TableCell>
                    <TableCell>
                      <Badge variant="outline">{u.applicationRole}</Badge>
                    </TableCell>
                    <TableCell>
                      {bindings.data?.data?.job?.jobTitleId != null
                        ? jobTitleName(jobTitleItems, bindings.data.data.job.jobTitleId, tc("notAvailable"))
                        : tc("notAvailable")}
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-1">
                        {(bindings.data?.data?.roles ?? []).map((rb) => (
                          <Badge key={rb.id} variant="secondary" className="gap-1">
                            {systemRoleName(systemRoleItems, rb.systemRoleId, tc("notAvailable"))}
                            {rb.override ? t("roles.overrideSuffix") : ""}
                          </Badge>
                        ))}
                      </div>
                    </TableCell>
                    <TableCell className="text-right">
                      <Button variant="outline" size="sm" onClick={() => selectUser(u.id)}>
                        {t("roles.bindRoles")}
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : null}
        </CardContent>
      </Card>

      {selectedUser ? (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center justify-between">
              <span>{t("roles.binding", { name: selectedUser.displayName ?? selectedUser.loginIdentifier })}</span>
              <Button variant="ghost" size="sm" onClick={() => setSelectedUserId(null)}>
                <XIcon />
              </Button>
            </CardTitle>
            <CardDescription>
              {t("roles.applicationRole")} <Badge variant="outline">{selectedUser.applicationRole}</Badge>
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-6">
            {bindings.isLoading ? <p className="text-sm text-muted-foreground">{t("roles.loadingBindings")}</p> : null}
            {bindings.isError ? <p className="text-sm text-destructive">{t("roles.bindingsLoadFailed")}</p> : null}
            {!bindings.isLoading && !bindings.isError ? (
              <>
                {/* Job title binding */}
                <form onSubmit={submitJob} className="space-y-2">
                  <label className="font-medium text-sm">{t("roles.jobTitlePanel")}</label>
                  <div className="flex items-end gap-2">
                    <Select
                      value={pendingJobTitleId}
                      onValueChange={setPendingJobTitleId}
                      disabled={setJob.isPending || !canMutate}
                    >
                      <SelectTrigger className="w-72">
                        <SelectValue placeholder={t("selectJobTitle")} />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value={NONE}>{t("roles.clearJobTitle")}</SelectItem>
                        {jobTitleItems.map((jt) => (
                          <SelectItem key={jt.id} value={jt.id}>
                            {jt.name} ({jt.code})
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    {canMutate ? (
                      <Button type="submit" size="sm" disabled={setJob.isPending}>
                        {setJob.isPending ? <Loader2Icon className="animate-spin" /> : null}
                        {tc("save")}
                      </Button>
                    ) : null}
                  </div>
                </form>

                {/* System role bindings */}
                <div className="space-y-2">
                  <label className="font-medium text-sm">{t("roles.systemRolesTitle")}</label>
                  {bindings.data?.data?.roles.length ? (
                    <div className="flex flex-wrap gap-2 mb-2">
                      {bindings.data.data.roles.map((rb) => (
                        <Badge key={rb.id} variant="secondary" className="gap-1 pr-1">
                          {systemRoleName(systemRoleItems, rb.systemRoleId, tc("notAvailable"))}
                          {rb.override ? t("roles.overrideSuffix") : ""}
                          {canMutate ? (
                            <button
                              type="button"
                              onClick={() => void handleRemoveRole(rb.id)}
                              disabled={removeRole.isPending}
                              className="ml-1 text-muted-foreground hover:text-destructive"
                              aria-label={t("roles.removeRoleAria", {
                                name: systemRoleName(systemRoleItems, rb.systemRoleId, tc("notAvailable")),
                              })}
                            >
                              <XIcon className="size-3" />
                            </button>
                          ) : null}
                        </Badge>
                      ))}
                    </div>
                  ) : (
                    <p className="text-sm text-muted-foreground">{t("roles.noSystemRoles")}</p>
                  )}
                  {canMutate ? (
                    <div className="flex items-end gap-2">
                      <Select value={pendingRoleId} onValueChange={setPendingRoleId}>
                        <SelectTrigger className="w-64">
                          <SelectValue placeholder={t("roles.addSystemRolePlaceholder")} />
                        </SelectTrigger>
                        <SelectContent>
                          {systemRoleItems
                            .filter((sr) => !bindings.data?.data?.roles?.some((b) => b.systemRoleId === sr.id))
                            .map((sr) => (
                              <SelectItem key={sr.id} value={sr.id}>
                                {sr.name} ({sr.code})
                              </SelectItem>
                            ))}
                        </SelectContent>
                      </Select>
                      <label className="flex items-center gap-1 text-sm">
                        <Checkbox checked={pendingOverride} onCheckedChange={(v) => setPendingOverride(Boolean(v))} />
                        {t("roles.override")}
                      </label>
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={addRole.isPending || pendingRoleId === NONE}
                        onClick={() => void submitAddRole()}
                      >
                        {addRole.isPending ? <Loader2Icon className="animate-spin" /> : null}
                        {t("roles.add")}
                      </Button>
                    </div>
                  ) : null}
                </div>
              </>
            ) : null}
          </CardContent>
        </Card>
      ) : null}
    </div>
  );
}

function jobTitleName(
  items: { id: string; name: string }[],
  id: string | null | undefined,
  notAvailable: string,
): string {
  const found = items.find((i) => i.id === id);
  return found?.name ?? id ?? notAvailable;
}

function systemRoleName(
  items: { id: string; name: string }[],
  id: string | null | undefined,
  notAvailable: string,
): string {
  const found = items.find((i) => i.id === id);
  return found?.name ?? id ?? notAvailable;
}

function State({ title, description, action }: { title: string; description: string; action?: React.ReactNode }) {
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

function TableSkeleton() {
  return (
    <div className="space-y-2">
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-10 w-full" />
    </div>
  );
}
