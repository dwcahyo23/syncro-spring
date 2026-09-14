"use client";

import { useMemo, useState } from "react";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { PlusIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { toast } from "sonner";

import { EntitySelect } from "@/components/syncro/entity-select";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { useListMachines } from "@/lib/api/generated/syncro";
import { syncroFetch } from "@/lib/api/orval-mutator";
import { useAuthUser } from "@/lib/auth/use-auth-user";

interface CategoryOption {
  code: string;
  label: string;
}

/** Roles mirrored from WorkOrderService.requireCreateRole — server stays authoritative;
 * the button just hides itself for roles that would only get a 403. */
const CREATABLE_ROLES = new Set<string>([
  "SUPER_ADMIN",
  "MANAGER_MAINTENANCE",
  "MAINTENANCE_LEADER",
  "SECTION_LEADER",
  "STAFF_MAINTENANCE",
  "PRODUCTION_LEADER",
]);

/** "Buat Work Order" dialog: machine (searchable) + category + description → POST /workorders. */
export function CreateWorkOrderDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const t = useTranslations("workOrders");
  const tc = useTranslations("common");
  const queryClient = useQueryClient();
  const authUser = useAuthUser();
  const [machineId, setMachineId] = useState("");
  const [categoryCode, setCategoryCode] = useState("");
  const [description, setDescription] = useState("");

  const { data: categoriesRes } = useQuery<CategoryOption[]>({
    queryKey: ["/api/v1/work-order-categories"],
    queryFn: async () => {
      const res = await syncroFetch<{ data: CategoryOption[] }>("/api/v1/work-order-categories", { method: "GET" });
      return res.data;
    },
    enabled: open,
    staleTime: 60_000,
  });

  const machines = useListMachines({ size: 200 }, { query: { enabled: open } });
  const machineOptions = useMemo(
    () =>
      (machines.data?.data?.items ?? []).map((m) => ({
        id: m.id ?? "",
        label: [m.code, m.name, m.plantCode].filter(Boolean).join(" · "),
      })),
    [machines.data],
  );

  const createMutation = useMutation({
    mutationFn: async () => {
      const res = await syncroFetch<{ data: { id: string } }>("/api/v1/workorders", {
        method: "POST",
        headers: { "Idempotency-Key": crypto.randomUUID() },
        body: JSON.stringify({
          categoryCode,
          machineId,
          description: description.trim() || undefined,
        }),
      });
      return res.data;
    },
    onSuccess: (created) => {
      void queryClient.invalidateQueries({ queryKey: ["/api/v1/workorders"] });
      void queryClient.invalidateQueries({ queryKey: ["/api/v1/workorders/kanban"] });
      toast.success(t("createDialog.created", { id: created.id }));
      onOpenChange(false);
      setMachineId("");
      setCategoryCode("");
      setDescription("");
    },
    onError: () => {
      toast.error(t("createDialog.failed"));
    },
  });

  const canSubmit = Boolean(machineId && categoryCode) && !createMutation.isPending;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <PlusIcon className="size-4" />
            {t("createDialog.title")}
          </DialogTitle>
          <DialogDescription>{t("createDialog.description")}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-1">
            <Label>{t("machine")}</Label>
            <EntitySelect
              value={machineId}
              options={machineOptions}
              onValueChange={setMachineId}
              placeholder={t("createDialog.machinePlaceholder")}
            />
          </div>
          <div className="space-y-1">
            <Label>{t("category")}</Label>
            <Select value={categoryCode} onValueChange={setCategoryCode}>
              <SelectTrigger aria-label={t("category")}>
                <SelectValue placeholder={t("createDialog.categoryPlaceholder")} />
              </SelectTrigger>
              <SelectContent>
                {(categoriesRes ?? []).map((c) => (
                  <SelectItem key={c.code} value={c.code}>
                    {c.code} · {c.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {authUser?.applicationRole === "PRODUCTION_LEADER" ? (
              <p className="text-muted-foreground text-xs">{t("createDialog.productionHint")}</p>
            ) : null}
          </div>
          <div className="space-y-1">
            <Label>{t("table.problem")}</Label>
            <Textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder={t("createDialog.descriptionPlaceholder")}
              rows={3}
              maxLength={4000}
            />
          </div>
          <div className="flex justify-end gap-2">
            <Button variant="ghost" size="sm" onClick={() => onOpenChange(false)}>
              {tc("cancel")}
            </Button>
            <Button size="sm" disabled={!canSubmit} onClick={() => createMutation.mutate()}>
              {createMutation.isPending ? tc("saving") : t("createDialog.save")}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** Toolbar button that mounts the dialog; hidden for roles the backend would reject. */
export function CreateWorkOrderButton() {
  const t = useTranslations("workOrders");
  const [open, setOpen] = useState(false);
  const authUser = useAuthUser();
  if (!authUser || !CREATABLE_ROLES.has(authUser.applicationRole)) return null;
  return (
    <>
      <Button type="button" size="sm" onClick={() => setOpen(true)} className="ml-auto">
        <PlusIcon className="mr-1 size-4" />
        {t("createDialog.trigger")}
      </Button>
      <CreateWorkOrderDialog open={open} onOpenChange={setOpen} />
    </>
  );
}
