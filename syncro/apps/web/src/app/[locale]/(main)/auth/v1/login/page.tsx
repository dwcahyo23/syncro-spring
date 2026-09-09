import { getTranslations } from "next-intl/server";

import { LoginForm } from "@/features/auth/login-form";

export default async function LoginV1() {
  const t = await getTranslations("auth");
  return (
    <main className="flex min-h-dvh items-center justify-center bg-background p-8">
      <div className="w-full max-w-md space-y-6 text-center">
        <div className="space-y-2">
          <p className="font-medium text-muted-foreground text-sm">{t("eyebrow")}</p>
          <h1 className="font-medium text-3xl">{t("title")}</h1>
          <p className="text-muted-foreground text-sm">{t("subtext")}</p>
        </div>
        <LoginForm />
      </div>
    </main>
  );
}
