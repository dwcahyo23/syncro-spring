"use client";

import { useTranslations } from "next-intl";

import { ModulePlaceholder } from "@/components/syncro/module-placeholder";
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

import { usePlantScope } from "./plant-scope-store";

type PlantScopedModulePlaceholderProps = {
  title: string;
  description: string;
  sections?: string[];
};

export function PlantScopedModulePlaceholder({ title, description, sections }: PlantScopedModulePlaceholderProps) {
  const t = useTranslations("plantScope");
  const { loadError, scope } = usePlantScope();

  const header = (
    <div className="space-y-2">
      <p className="font-medium text-muted-foreground text-sm">{t("shellLabel")}</p>
      <h1 className="font-semibold text-3xl tracking-tight">{title}</h1>
      <p className="max-w-3xl text-muted-foreground">{description}</p>
    </div>
  );

  if (loadError) {
    return (
      <main className="mx-auto flex w-full max-w-6xl flex-col gap-6">
        {header}
        <Card>
          <CardHeader>
            <CardTitle>{t("unavailableTitle")}</CardTitle>
            <CardDescription>{t("unavailableDescription")}</CardDescription>
          </CardHeader>
        </Card>
      </main>
    );
  }

  if (!scope) {
    return (
      <main className="mx-auto flex w-full max-w-6xl flex-col gap-6">
        {header}
        <Card>
          <CardHeader>
            <CardTitle>{t("loadingTitle")}</CardTitle>
            <CardDescription>{t("loadingDescription")}</CardDescription>
          </CardHeader>
        </Card>
      </main>
    );
  }

  if (scope.mode === "EMPTY") {
    return (
      <main className="mx-auto flex w-full max-w-6xl flex-col gap-6">
        {header}
        <Card>
          <CardHeader>
            <CardTitle>{t("emptyTitle")}</CardTitle>
            <CardDescription>{t("emptyDescription")}</CardDescription>
          </CardHeader>
        </Card>
      </main>
    );
  }

  return <ModulePlaceholder description={description} sections={sections} title={title} />;
}
