"use client";

import { useState } from "react";

import { useQuery } from "@tanstack/react-query";
import { PlusIcon } from "lucide-react";
import { useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { syncroFetch } from "@/lib/api/orval-mutator";

interface WorkOrderCategory {
  code: string;
  label: string;
  targetResponseMinutes: number | null;
}

export function WorkOrderCategoryManagement() {
  const t = useTranslations("workOrders");
  const tc = useTranslations("common");
  const { data, isLoading, refetch } = useQuery<WorkOrderCategory[]>({
    queryKey: ["/api/v1/work-order-categories"],
    queryFn: async () => {
      const res = await syncroFetch<{ data: WorkOrderCategory[] }>("/api/v1/work-order-categories", { method: "GET" });
      return res.data;
    },
    staleTime: 60_000,
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center justify-between">
          <span>{t("categoryManagement.title")}</span>
          <CreateCategoryDialog onCreated={() => void refetch()} />
        </CardTitle>
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <div className="space-y-2">
            <Skeleton className="h-8 w-full" />
            <Skeleton className="h-8 w-full" />
          </div>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{tc("code")}</TableHead>
                <TableHead>{t("categoryManagement.label")}</TableHead>
                <TableHead>{t("categoryManagement.targetResponseCol")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(data ?? []).length === 0 ? (
                <TableRow>
                  <TableCell colSpan={3} className="text-center text-muted-foreground">
                    {t("categoryManagement.empty")}
                  </TableCell>
                </TableRow>
              ) : (
                (data ?? []).map((category) => (
                  <TableRow key={category.code}>
                    <TableCell>
                      <Badge variant="outline">{category.code}</Badge>
                    </TableCell>
                    <TableCell className="text-sm">{category.label}</TableCell>
                    <TableCell className="text-xs text-muted-foreground">
                      {category.targetResponseMinutes != null
                        ? t("categoryManagement.minutes", { count: category.targetResponseMinutes })
                        : t("dash")}
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
}

function CreateCategoryDialog({ onCreated }: { onCreated: () => void }) {
  const t = useTranslations("workOrders");
  const tc = useTranslations("common");
  const [open, setOpen] = useState(false);
  const [code, setCode] = useState("");
  const [label, setLabel] = useState("");
  const [targetResponseMinutes, setTargetResponseMinutes] = useState("");
  const [error, setError] = useState("");

  const handleCreate = async () => {
    setError("");
    try {
      await syncroFetch("/api/v1/work-order-categories", {
        method: "POST",
        body: JSON.stringify({
          code: code.trim(),
          label: label.trim(),
          targetResponseMinutes: targetResponseMinutes ? Number(targetResponseMinutes) : null,
        }),
      });
      setOpen(false);
      setCode("");
      setLabel("");
      setTargetResponseMinutes("");
      onCreated();
    } catch (e) {
      setError(t("categoryManagement.createFailed"));
    }
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button type="button" size="sm">
          <PlusIcon className="mr-1 size-4" />
          {t("categoryManagement.add")}
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("categoryManagement.dialogTitle")}</DialogTitle>
          <DialogDescription>{t("categoryManagement.dialogDescription")}</DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <div className="space-y-1">
            <Label>{tc("code")}</Label>
            <Input
              placeholder={t("categoryManagement.codePlaceholder")}
              value={code}
              onChange={(e) => setCode(e.target.value)}
            />
          </div>
          <div className="space-y-1">
            <Label>{t("categoryManagement.label")}</Label>
            <Input
              placeholder={t("categoryManagement.labelPlaceholder")}
              value={label}
              onChange={(e) => setLabel(e.target.value)}
            />
          </div>
          <div className="space-y-1">
            <Label>{t("categoryManagement.targetResponseLabel")}</Label>
            <Input
              type="number"
              min={0}
              placeholder={t("categoryManagement.targetResponsePlaceholder")}
              value={targetResponseMinutes}
              onChange={(e) => setTargetResponseMinutes(e.target.value)}
            />
          </div>
          {error && <p className="text-destructive text-xs">{error}</p>}
        </div>
        <DialogFooter>
          <Button variant="ghost" size="sm" onClick={() => setOpen(false)}>
            {tc("cancel")}
          </Button>
          <Button size="sm" onClick={handleCreate} disabled={!code.trim() || !label.trim()}>
            {tc("create")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
