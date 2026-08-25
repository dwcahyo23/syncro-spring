"use client";

import { type FormEvent, useMemo, useState } from "react";

import { useQueryClient } from "@tanstack/react-query";
import { Loader2Icon, TriangleAlertIcon, UsersIcon, WrenchIcon } from "lucide-react";
import { toast } from "sonner";

import {
  AlertDialog,
  AlertDialogAction,
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
import type { CreateTeamRequest, TeamDetailResponse, TeamView } from "@/lib/api/generated/model";
import {
  getGetTeamQueryKey,
  getListTeamsQueryKey,
  useAddTeamMember,
  useCreateTeam,
  useDeleteTeam,
  useGetTeam,
  useLinkTeamMachine,
  useListMachines,
  useListPlants,
  useListTeams,
  useListUsers,
  useRemoveTeamMember,
  useUnlinkTeamMachine,
  useUpdateTeam,
} from "@/lib/api/generated/syncro";
import { SyncroApiError } from "@/lib/api/orval-mutator";
import { useAuthUser } from "@/lib/auth/use-auth-user";

type ErrorResponse = {
  code: string;
  message: string;
  fieldErrors?: Record<string, string>;
};

type DialogMode = { type: "create" | "edit"; team?: TeamView };

interface TeamFormState {
  name: string;
  expiresAt: string;
}

const EMPTY_FORM: TeamFormState = { name: "", expiresAt: "" };

type ManageDialog = { type: "members"; team: TeamView } | { type: "machines"; team: TeamView };

function toDatetimeLocal(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "";
  }
  // Second precision: a minute-only value would silently rewrite the stored expiry
  // by up to 59s whenever the user saves an untouched expiry field.
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 19);
}

function fromDatetimeLocal(value: string): string {
  return new Date(value).toISOString();
}

export function TeamManagement() {
  const user = useAuthUser();
  const queryClient = useQueryClient();
  const canMutate = user?.applicationRole === "SUPER_ADMIN" || user?.applicationRole === "MANAGE";

  const teamsQuery = useListTeams({ query: { enabled: Boolean(user) } });
  const invalidateTeams = () => queryClient.invalidateQueries({ queryKey: getListTeamsQueryKey() });
  const createTeam = useCreateTeam({ mutation: { onSuccess: invalidateTeams } });
  const updateTeam = useUpdateTeam({ mutation: { onSuccess: invalidateTeams } });
  const deleteTeam = useDeleteTeam({ mutation: { onSuccess: invalidateTeams } });

  const [dialogMode, setDialogMode] = useState<DialogMode | null>(null);
  const [form, setForm] = useState<TeamFormState>(EMPTY_FORM);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [teamToDelete, setTeamToDelete] = useState<TeamView | null>(null);
  const [manageDialog, setManageDialog] = useState<ManageDialog | null>(null);

  const listEnvelope = teamsQuery.data?.data;
  // Error responses carry no body; only the 200 envelope has `items`.
  const items = listEnvelope && "items" in listEnvelope ? listEnvelope.items : [];
  const isSaving = createTeam.isPending || updateTeam.isPending;
  const isDeleting = deleteTeam.isPending;

  function openCreateDialog() {
    setDialogMode({ type: "create" });
    setForm(EMPTY_FORM);
    setFieldErrors({});
    setFormError(null);
  }

  function openEditDialog(team: TeamView) {
    setDialogMode({ type: "edit", team });
    setForm({ name: team.name, expiresAt: toDatetimeLocal(team.expiresAt) });
    setFieldErrors({});
    setFormError(null);
  }

  async function submitTeam(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFieldErrors({});
    setFormError(null);

    let expiresAt: string;
    try {
      expiresAt = fromDatetimeLocal(form.expiresAt);
    } catch {
      setFieldErrors({ expiresAt: "Invalid value." });
      return;
    }

    try {
      if (dialogMode?.type === "edit" && dialogMode.team) {
        await updateTeam.mutateAsync({
          teamId: dialogMode.team.id,
          data: { name: form.name.trim(), expiresAt },
        });
        toast.success("Team updated.");
      } else {
        const payload: CreateTeamRequest = {
          name: form.name.trim(),
          expiresAt,
        };
        await createTeam.mutateAsync({ data: payload });
        toast.success("Team created.");
      }
      setDialogMode(null);
    } catch (error) {
      const response = errorResponse(error);
      setFieldErrors(response?.fieldErrors ?? {});
      setFormError(response?.message ?? "Team request failed.");
      toast.error(response?.message ?? "Team request failed.");
    }
  }

  async function confirmDelete() {
    if (!teamToDelete) {
      return;
    }
    try {
      await deleteTeam.mutateAsync({ teamId: teamToDelete.id ?? "" });
      toast.success("Team deleted.");
      setTeamToDelete(null);
    } catch (error) {
      const response = errorResponse(error);
      toast.error(response?.message ?? "Team delete failed.");
      setTeamToDelete(null);
    }
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Cross-Plant Teams</CardTitle>
          <CardDescription>
            Expiry-dated groups of members and target machines. Members gain scoped access to the targeted machines
            across plants while the team is active.
          </CardDescription>
          <CardAction className="flex items-center gap-2">
            {canMutate ? (
              <Button onClick={openCreateDialog}>Create team</Button>
            ) : (
              <Badge variant="secondary">Read-only</Badge>
            )}
          </CardAction>
        </CardHeader>
        <CardContent>
          {teamsQuery.isLoading ? <TeamTableSkeleton /> : null}
          {teamsQuery.isError ? (
            <TeamState
              title="Teams could not be loaded"
              description="Refresh page or contact administrator if access should be available."
              action={
                <Button variant="outline" onClick={() => teamsQuery.refetch()}>
                  Retry
                </Button>
              }
            />
          ) : null}
          {!teamsQuery.isLoading && !teamsQuery.isError && items.length === 0 ? (
            <TeamState title="No teams yet" description="Create the first cross-plant team." />
          ) : null}
          {items.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Members</TableHead>
                  <TableHead>Machines</TableHead>
                  <TableHead>Expires at</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead className="text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((team) => (
                  <TableRow key={team.id}>
                    <TableCell className="font-medium">{team.name}</TableCell>
                    <TableCell>{team.memberCount}</TableCell>
                    <TableCell>{team.machineCount}</TableCell>
                    <TableCell>{formatExpiry(team.expiresAt)}</TableCell>
                    <TableCell>
                      {team.active ? (
                        <Badge variant="outline">Active</Badge>
                      ) : (
                        <Badge variant="secondary">Expired</Badge>
                      )}
                    </TableCell>
                    <TableCell className="text-right">
                      {canMutate ? (
                        <div className="flex justify-end gap-2">
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={() => setManageDialog({ type: "members", team })}
                          >
                            <UsersIcon />
                            Members
                          </Button>
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={() => setManageDialog({ type: "machines", team })}
                          >
                            <WrenchIcon />
                            Machines
                          </Button>
                          <Button variant="outline" size="sm" onClick={() => openEditDialog(team)}>
                            Edit
                          </Button>
                          <Button variant="destructive" size="sm" onClick={() => setTeamToDelete(team)}>
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
          ) : null}
        </CardContent>
      </Card>

      <Dialog open={dialogMode !== null} onOpenChange={(open) => !open && setDialogMode(null)}>
        <DialogContent>
          <form onSubmit={submitTeam} className="space-y-4">
            <DialogHeader>
              <DialogTitle>{dialogMode?.type === "edit" ? "Edit team" : "Create team"}</DialogTitle>
              <DialogDescription>
                Team names must be unique (case-insensitive). Expiry must be strictly in the future.
              </DialogDescription>
            </DialogHeader>
            {formError ? (
              <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">{formError}</p>
            ) : null}
            <div className="grid gap-2">
              <Label htmlFor="team-name">Name</Label>
              <Input
                id="team-name"
                value={form.name}
                onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
                aria-invalid={Boolean(fieldErrors.name)}
                disabled={isSaving}
              />
              {fieldErrors.name ? <p className="text-destructive text-sm">{fieldErrors.name}</p> : null}
            </div>
            <div className="grid gap-2">
              <Label htmlFor="team-expiry">Expires at</Label>
              <Input
                id="team-expiry"
                type="datetime-local"
                step={1}
                value={form.expiresAt}
                onChange={(event) => setForm((current) => ({ ...current, expiresAt: event.target.value }))}
                aria-invalid={Boolean(fieldErrors.expiresAt)}
                disabled={isSaving}
              />
              {fieldErrors.expiresAt ? <p className="text-destructive text-sm">{fieldErrors.expiresAt}</p> : null}
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setDialogMode(null)} disabled={isSaving}>
                Cancel
              </Button>
              <Button type="submit" disabled={isSaving}>
                {isSaving ? <Loader2Icon className="animate-spin" /> : null}
                Save team
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <AlertDialog open={teamToDelete !== null} onOpenChange={(open) => !open && setTeamToDelete(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete team "{teamToDelete?.name}"?</AlertDialogTitle>
            <AlertDialogDescription>
              This permanently deletes the team and its member and machine links. This action cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={isDeleting}>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={() => void confirmDelete()} disabled={isDeleting}>
              {isDeleting ? <Loader2Icon className="animate-spin" /> : null}
              Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {manageDialog ? (
        <ManageDialog
          team={manageDialog.team}
          type={manageDialog.type}
          canMutate={canMutate}
          onClose={() => setManageDialog(null)}
          onChanged={invalidateTeams}
        />
      ) : null}
    </div>
  );
}

function ManageDialog({
  team,
  type,
  canMutate,
  onClose,
  onChanged,
}: {
  team: TeamView;
  type: "members" | "machines";
  canMutate: boolean;
  onClose: () => void;
  onChanged: () => void;
}) {
  const queryClient = useQueryClient();
  const detail = useGetTeam(team.id);
  const detailEnvelope = detail.data?.data;
  const detailData = detailEnvelope && "members" in detailEnvelope ? detailEnvelope : null;

  // The list key alone never matches the orval detail key (single string element
  // '/api/v1/teams/${teamId}'), so mutations must invalidate the detail explicitly.
  const refreshDialog = () => {
    onChanged();
    queryClient.invalidateQueries({ queryKey: getGetTeamQueryKey(team.id) });
  };

  let manager: React.ReactNode = null;
  if (detailData && type === "members") {
    manager = <MemberManager teamId={team.id} detail={detailData} canMutate={canMutate} onChanged={refreshDialog} />;
  } else if (detailData && type === "machines") {
    manager = <MachineManager teamId={team.id} detail={detailData} canMutate={canMutate} onChanged={refreshDialog} />;
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[80vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>
            {type === "members" ? "Team members" : "Target machines"} — {team.name}
          </DialogTitle>
          <DialogDescription>
            {type === "members"
              ? "Members gain scoped access to the team's machines across plants."
              : "Target machines resolve to their machine groups for the member's operational scope."}
          </DialogDescription>
        </DialogHeader>
        {detail.isLoading ? <TeamTableSkeleton /> : null}
        {detail.isError ? (
          <p className="rounded-md bg-destructive/10 p-2 text-destructive text-sm">Team details could not be loaded.</p>
        ) : null}
        {manager}
      </DialogContent>
    </Dialog>
  );
}

function MemberManager({
  teamId,
  detail,
  canMutate,
  onChanged,
}: {
  teamId: string;
  detail: TeamDetailResponse;
  canMutate: boolean;
  onChanged: () => void;
}) {
  const usersQuery = useListUsers({ query: { enabled: canMutate } });
  const users = usersQuery.data?.data ?? [];
  const [selectedUserId, setSelectedUserId] = useState("");
  const addMember = useAddTeamMember({
    mutation: { onSuccess: onChanged },
  });
  const removeMember = useRemoveTeamMember({
    mutation: { onSuccess: onChanged },
  });

  const memberIds = new Set(detail.members.map((member) => member.userId));
  const availableUsers = users.filter((candidate) => candidate.id && !memberIds.has(candidate.id));

  async function handleAdd() {
    if (!selectedUserId) {
      return;
    }
    try {
      await addMember.mutateAsync({ teamId, data: { userId: selectedUserId } });
      setSelectedUserId("");
      toast.success("Member added.");
    } catch (error) {
      toast.error(errorMessage(error) ?? "Member add failed.");
    }
  }

  async function handleRemove(userId: string) {
    try {
      await removeMember.mutateAsync({ teamId, userId });
      toast.success("Member removed.");
    } catch (error) {
      toast.error(errorMessage(error) ?? "Member remove failed.");
    }
  }

  return (
    <div className="space-y-4">
      {canMutate ? (
        availableUsers.length === 0 ? (
          <p className="text-muted-foreground text-sm">All users are already members of this team.</p>
        ) : (
          <div className="grid gap-2">
            <Label htmlFor="team-member-select">Add member</Label>
            <div className="flex gap-2">
              <Select value={selectedUserId} onValueChange={setSelectedUserId}>
                <SelectTrigger id="team-member-select" className="w-full">
                  <SelectValue placeholder="Select user" />
                </SelectTrigger>
                <SelectContent>
                  {availableUsers.map((candidate) => (
                    <SelectItem key={candidate.id} value={candidate.id ?? ""}>
                      {candidate.loginIdentifier} — {candidate.applicationRole}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <Button type="button" variant="outline" onClick={() => void handleAdd()} disabled={!selectedUserId}>
                Add
              </Button>
            </div>
          </div>
        )
      ) : null}
      <div className="space-y-2">
        {detail.members.length === 0 ? (
          <p className="text-muted-foreground text-sm">No members yet.</p>
        ) : (
          detail.members.map((member) => (
            <div key={member.userId} className="flex items-center justify-between rounded-md border p-2">
              <div>
                <p className="font-medium text-sm">{member.loginIdentifier}</p>
                <p className="text-muted-foreground text-xs">{member.userId}</p>
              </div>
              {canMutate ? (
                <Button
                  variant="outline"
                  size="sm"
                  disabled={removeMember.isPending}
                  onClick={() => void handleRemove(member.userId)}
                >
                  Remove
                </Button>
              ) : null}
            </div>
          ))
        )}
      </div>
    </div>
  );
}

function MachineManager({
  teamId,
  detail,
  canMutate,
  onChanged,
}: {
  teamId: string;
  detail: TeamDetailResponse;
  canMutate: boolean;
  onChanged: () => void;
}) {
  const [plantFilter, setPlantFilter] = useState("all");
  const machineParams = useMemo(
    () => ({ plantId: plantFilter === "all" ? undefined : plantFilter, limit: 200 }),
    [plantFilter],
  );
  const machinesQuery = useListMachines(machineParams, { query: { enabled: canMutate } });
  const machines = machinesQuery.data?.data.items ?? [];
  // Plant options come from the plants master list, not the (possibly truncated)
  // machine page — every plant stays filterable and no duplicate keys can occur.
  const plantsQuery = useListPlants({ query: { enabled: canMutate } });
  const plantOptions = (plantsQuery.data?.data.items ?? []).filter((plant) => plant.id && plant.code);
  const [selectedMachineId, setSelectedMachineId] = useState("");
  const linkMachine = useLinkTeamMachine({
    mutation: { onSuccess: onChanged },
  });
  const unlinkMachine = useUnlinkTeamMachine({
    mutation: { onSuccess: onChanged },
  });

  const machineIds = new Set(detail.machines.map((machine) => machine.machineId));
  const availableMachines = machines.filter((candidate) => candidate.id && !machineIds.has(candidate.id));

  function handlePlantFilterChange(value: string) {
    // A stale selection could link a machine that is invisible under the new filter.
    setPlantFilter(value);
    setSelectedMachineId("");
  }

  async function handleLink() {
    if (!selectedMachineId) {
      return;
    }
    try {
      await linkMachine.mutateAsync({ teamId, data: { machineId: selectedMachineId } });
      setSelectedMachineId("");
      toast.success("Machine linked.");
    } catch (error) {
      toast.error(errorMessage(error) ?? "Machine link failed.");
    }
  }

  async function handleUnlink(machineId: string) {
    try {
      await unlinkMachine.mutateAsync({ teamId, machineId });
      toast.success("Machine unlinked.");
    } catch (error) {
      toast.error(errorMessage(error) ?? "Machine unlink failed.");
    }
  }

  return (
    <div className="space-y-4">
      {canMutate ? (
        availableMachines.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            No linkable machines in this view — every listed machine is already linked, or none exist yet.
          </p>
        ) : (
          <div className="space-y-2">
            <div className="grid gap-2">
              <Label htmlFor="team-machine-plant">Plant filter</Label>
              <Select value={plantFilter} onValueChange={handlePlantFilterChange}>
                <SelectTrigger id="team-machine-plant">
                  <SelectValue placeholder="All plants" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">All plants</SelectItem>
                  {plantOptions.map((plant) => (
                    <SelectItem key={plant.id} value={plant.id ?? ""}>
                      {plant.code}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="grid gap-2">
              <Label htmlFor="team-machine-select">Link machine</Label>
              <div className="flex gap-2">
                <Select value={selectedMachineId} onValueChange={setSelectedMachineId}>
                  <SelectTrigger id="team-machine-select" className="w-full">
                    <SelectValue placeholder="Select machine" />
                  </SelectTrigger>
                  <SelectContent>
                    {availableMachines.map((candidate) => (
                      <SelectItem key={candidate.id} value={candidate.id ?? ""}>
                        {candidate.code} — {candidate.plantCode} / {candidate.machineGroupName}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <Button type="button" variant="outline" onClick={() => void handleLink()} disabled={!selectedMachineId}>
                  Link
                </Button>
              </div>
            </div>
          </div>
        )
      ) : null}
      <div className="space-y-2">
        {detail.machines.length === 0 ? (
          <p className="text-muted-foreground text-sm">No machines linked yet.</p>
        ) : (
          detail.machines.map((machine) => (
            <div key={machine.machineId} className="flex items-center justify-between rounded-md border p-2">
              <div>
                <p className="font-medium text-sm">
                  {machine.code} — {machine.name}
                </p>
                <p className="text-muted-foreground text-xs">
                  {machine.plantCode} / {machine.machineGroupName}
                </p>
              </div>
              {canMutate ? (
                <Button
                  variant="outline"
                  size="sm"
                  disabled={unlinkMachine.isPending}
                  onClick={() => void handleUnlink(machine.machineId)}
                >
                  Unlink
                </Button>
              ) : null}
            </div>
          ))
        )}
      </div>
    </div>
  );
}

function TeamState({ title, description, action }: { title: string; description: string; action?: React.ReactNode }) {
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

function TeamTableSkeleton() {
  return (
    <div className="space-y-2">
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-10 w-full" />
    </div>
  );
}

function formatExpiry(expiresAt: string | undefined): string {
  if (!expiresAt) {
    return "-";
  }
  const date = new Date(expiresAt);
  return Number.isNaN(date.getTime()) ? expiresAt : date.toLocaleString();
}

function errorResponse(error: unknown): ErrorResponse | null {
  if (!(error instanceof SyncroApiError) || !error.payload || typeof error.payload !== "object") {
    return null;
  }
  const payload = error.payload as ErrorResponse;
  return typeof payload.code === "string" && typeof payload.message === "string" ? payload : null;
}

function errorMessage(error: unknown): string | null {
  return errorResponse(error)?.message ?? null;
}
