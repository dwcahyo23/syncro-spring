"use client";

import { ModulePlaceholder } from "@/components/syncro/module-placeholder";
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

import { usePlantScope } from "./plant-scope-store";

type PlantScopedModulePlaceholderProps = {
  title: string;
  description: string;
  sections?: string[];
};

export function PlantScopedModulePlaceholder({ title, description, sections }: PlantScopedModulePlaceholderProps) {
  const { loadError, scope } = usePlantScope();

  if (loadError) {
    return (
      <main className="mx-auto flex w-full max-w-6xl flex-col gap-6">
        <div className="space-y-2">
          <p className="font-medium text-muted-foreground text-sm">Syncro shell</p>
          <h1 className="font-semibold text-3xl tracking-tight">{title}</h1>
          <p className="max-w-3xl text-muted-foreground">{description}</p>
        </div>
        <Card>
          <CardHeader>
            <CardTitle>Plant scope unavailable</CardTitle>
            <CardDescription>Plant scope could not be loaded. Try again or contact your administrator.</CardDescription>
          </CardHeader>
        </Card>
      </main>
    );
  }

  if (!scope) {
    return (
      <main className="mx-auto flex w-full max-w-6xl flex-col gap-6">
        <div className="space-y-2">
          <p className="font-medium text-muted-foreground text-sm">Syncro shell</p>
          <h1 className="font-semibold text-3xl tracking-tight">{title}</h1>
          <p className="max-w-3xl text-muted-foreground">{description}</p>
        </div>
        <Card>
          <CardHeader>
            <CardTitle>Loading plant scope</CardTitle>
            <CardDescription>Checking assigned plants before showing this module.</CardDescription>
          </CardHeader>
        </Card>
      </main>
    );
  }

  if (scope.mode === "EMPTY") {
    return (
      <main className="mx-auto flex w-full max-w-6xl flex-col gap-6">
        <div className="space-y-2">
          <p className="font-medium text-muted-foreground text-sm">Syncro shell</p>
          <h1 className="font-semibold text-3xl tracking-tight">{title}</h1>
          <p className="max-w-3xl text-muted-foreground">{description}</p>
        </div>
        <Card>
          <CardHeader>
            <CardTitle>No plants assigned</CardTitle>
            <CardDescription>No plants assigned. Contact your administrator.</CardDescription>
          </CardHeader>
        </Card>
      </main>
    );
  }

  return <ModulePlaceholder description={description} sections={sections} title={title} />;
}
