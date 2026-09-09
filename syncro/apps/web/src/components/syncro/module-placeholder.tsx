"use client";

import { useTranslations } from "next-intl";

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

interface ModulePlaceholderProps {
  title: string;
  description: string;
  sections?: string[];
}

export function ModulePlaceholder({ title, description, sections = [] }: ModulePlaceholderProps) {
  const t = useTranslations("common");
  return (
    <main className="mx-auto flex w-full max-w-6xl flex-col gap-6">
      <div className="space-y-2">
        <p className="font-medium text-muted-foreground text-sm">{t("shellLabel")}</p>
        <h1 className="font-semibold text-3xl tracking-tight">{title}</h1>
        <p className="max-w-3xl text-muted-foreground">{description}</p>
      </div>
      <Card>
        <CardHeader>
          <CardTitle>{t("modulePlaceholderTitle")}</CardTitle>
          <CardDescription>{t("modulePlaceholderDescription")}</CardDescription>
        </CardHeader>
        {sections.length > 0 ? (
          <CardContent>
            <ul className="grid gap-3 text-sm md:grid-cols-2 lg:grid-cols-3">
              {sections.map((section) => (
                <li className="rounded-lg border bg-card p-3 text-card-foreground" key={section}>
                  {section}
                </li>
              ))}
            </ul>
          </CardContent>
        ) : null}
      </Card>
    </main>
  );
}
