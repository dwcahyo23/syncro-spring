"use client";

import type { ReactNode } from "react";

import Link from "next/link";

import { useTranslations } from "next-intl";

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import type { AuthUser } from "@/lib/auth/auth-session";
import { useAuthUser } from "@/lib/auth/use-auth-user";

interface RoleGuardProps {
  readonly allowedRoles: readonly AuthUser["applicationRole"][];
  readonly title: string;
  readonly children: ReactNode;
}

export function RoleGuard({ allowedRoles, title, children }: RoleGuardProps) {
  const user = useAuthUser();

  if (!user || !allowedRoles.includes(user.applicationRole)) {
    return <ForbiddenState title={title} />;
  }

  return children;
}

function ForbiddenState({ title }: { readonly title: string }) {
  const t = useTranslations("common");
  return (
    <main className="mx-auto flex w-full max-w-6xl flex-col gap-6">
      <div className="space-y-2">
        <p className="font-medium text-muted-foreground text-sm">{t("shellLabel")}</p>
        <h1 className="font-semibold text-3xl tracking-tight">{title}</h1>
      </div>
      <Card>
        <CardHeader>
          <CardTitle>{t("permissionDeniedTitle")}</CardTitle>
          <CardDescription>{t("permissionDeniedDescription")}</CardDescription>
        </CardHeader>
        <CardContent>
          <Link className="font-medium text-primary underline-offset-4 hover:underline" href="/operations-overview">
            {t("goToOperationsOverview")}
          </Link>
        </CardContent>
      </Card>
    </main>
  );
}
