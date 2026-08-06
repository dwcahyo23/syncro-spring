"use client";

import { useState } from "react";

import { useQueryClient } from "@tanstack/react-query";
import { Loader2Icon, Trash2 } from "lucide-react";
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
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { usePlantScope } from "@/features/plant-scope/plant-scope-store";
import {
  getListQueryKey,
  useAssign,
  useList,
  useListMachines,
  useListUsers,
  useUnassign,
} from "@/lib/api/generated/syncro";
import { useAuthUser } from "@/lib/auth/use-auth-user";

export function ResponsibilityManagement() {
  const queryClient = useQueryClient();
  const user = useAuthUser();
  const plantScope = usePlantScope();
  const isViewer = user?.applicationRole === "VIEWER";
  const plantId = plantScope.activePlantId === "all" ? undefined : plantScope.activePlantId;

  const { data: machinesRes, isLoading: isLoadingMachines } = useListMachines({
    plantId,
    page: 0,
    size: 100,
  });

  const { data: usersRes, isLoading: isLoadingUsers } = useListUsers();

  const { data: responsibilitiesRes, isLoading: isLoadingResponsibilities } = useList({
    pageable: { page: 0, size: 100 },
  });

  const { mutate: assign, isPending: isAssigning } = useAssign({
    mutation: {
      onSuccess: () => {
        toast.success("Successfully assigned responsibility");
        setMachineId("");
        setUserId("");
        setLevel("");
        queryClient.invalidateQueries({ queryKey: getListQueryKey() });
      },
      onError: (error: any) => {
        const errorData = error.response?.data;
        if (errorData?.code === "DUPLICATE_RESPONSIBILITY") {
          toast.error("User is already assigned to this machine");
        } else {
          toast.error("Failed to assign responsibility");
        }
      },
    },
  });

  const { mutate: unassign, isPending: isUnassigning } = useUnassign({
    mutation: {
      onSuccess: () => {
        toast.success("Successfully unassigned responsibility");
        setResponsibilityToDelete(null);
        queryClient.invalidateQueries({ queryKey: getListQueryKey() });
      },
      onError: () => {
        toast.error("Failed to unassign responsibility");
      },
    },
  });

  const [machineId, setMachineId] = useState("");
  const [userId, setUserId] = useState("");
  const [level, setLevel] = useState("");

  const [responsibilityToDelete, setResponsibilityToDelete] = useState<string | null>(null);

  const handleAssign = (e: React.FormEvent) => {
    e.preventDefault();
    if (!machineId || !userId || !level) return;
    assign({ data: { machineId, userId, level: level as any } });
  };

  return (
    <div className="flex flex-col gap-6">
      <Card>
        <CardHeader>
          <CardTitle>Assign Responsibility</CardTitle>
          <CardDescription>
            Assign a user responsibility for a specific machine. Note: The MANAGE application role is distinct from the
            MANAGER machine responsibility level.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {isViewer ? (
            <div className="p-4 bg-muted text-muted-foreground rounded-md text-sm">
              You do not have permission to assign responsibilities.
            </div>
          ) : (
            <form onSubmit={handleAssign} className="grid grid-cols-1 md:grid-cols-4 gap-4 items-end">
              <div className="space-y-2">
                <Label htmlFor="machine">Machine</Label>
                <Select value={machineId} onValueChange={setMachineId}>
                  <SelectTrigger aria-label="Machine" disabled={isLoadingMachines}>
                    <SelectValue placeholder={isLoadingMachines ? "Loading..." : "Select machine"} />
                  </SelectTrigger>
                  <SelectContent>
                    {machinesRes?.data?.items?.map((m) => (
                      <SelectItem key={m.id} value={m.id ?? ""}>
                        {m.code}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <div className="space-y-2">
                <Label htmlFor="user">User</Label>
                <Select value={userId} onValueChange={setUserId}>
                  <SelectTrigger aria-label="User" disabled={isLoadingUsers}>
                    <SelectValue placeholder={isLoadingUsers ? "Loading..." : "Select user"} />
                  </SelectTrigger>
                  <SelectContent>
                    {usersRes?.data?.map((u) => (
                      <SelectItem key={u.id} value={u.id ?? ""}>
                        {u.loginIdentifier}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <div className="space-y-2">
                <Label htmlFor="level">Level</Label>
                <Select value={level} onValueChange={setLevel}>
                  <SelectTrigger aria-label="Responsibility level">
                    <SelectValue placeholder="Select level" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="TECHNICIAN">Technician</SelectItem>
                    <SelectItem value="STAFF">Staff</SelectItem>
                    <SelectItem value="LEADER">Leader</SelectItem>
                    <SelectItem value="SPV">Supervisor</SelectItem>
                    <SelectItem value="MANAGER">Manager</SelectItem>
                  </SelectContent>
                </Select>
              </div>

              <Button type="submit" disabled={!machineId || !userId || !level || isAssigning}>
                {isAssigning ? <Loader2Icon className="mr-2 h-4 w-4 animate-spin" /> : null}
                Assign
              </Button>
            </form>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Current Assignments</CardTitle>
          <CardDescription>View all active machine responsibility assignments.</CardDescription>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Machine</TableHead>
                <TableHead>User</TableHead>
                <TableHead>Level</TableHead>
                {!isViewer && <TableHead className="w-[100px] text-right">Actions</TableHead>}
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoadingResponsibilities ? (
                <TableRow>
                  <TableCell colSpan={4} className="text-center text-muted-foreground h-24">
                    <Loader2Icon className="mx-auto h-6 w-6 animate-spin" />
                  </TableCell>
                </TableRow>
              ) : !responsibilitiesRes?.data?.items?.length ? (
                <TableRow>
                  <TableCell colSpan={4} className="text-center text-muted-foreground h-24">
                    No responsibilities assigned.
                  </TableCell>
                </TableRow>
              ) : (
                responsibilitiesRes.data.items.map((r) => (
                  <TableRow key={r.id}>
                    <TableCell>
                      {machinesRes?.data?.items?.find((m) => m.id === r.machineId)?.code || r.machineId}
                    </TableCell>
                    <TableCell>{r.userName}</TableCell>
                    <TableCell>{r.level}</TableCell>
                    {!isViewer && (
                      <TableCell className="text-right">
                        <Button
                          variant="ghost"
                          size="icon"
                          className="text-destructive hover:text-destructive hover:bg-destructive/10"
                          onClick={() => setResponsibilityToDelete(r.id ?? null)}
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </TableCell>
                    )}
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <AlertDialog open={!!responsibilityToDelete} onOpenChange={(open) => !open && setResponsibilityToDelete(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Are you sure?</AlertDialogTitle>
            <AlertDialogDescription>
              This will unassign the user from the machine. This action cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              onClick={() => responsibilityToDelete && unassign({ id: responsibilityToDelete })}
              disabled={isUnassigning}
            >
              {isUnassigning ? <Loader2Icon className="mr-2 h-4 w-4 animate-spin" /> : null}
              Unassign
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
