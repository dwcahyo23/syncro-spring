import { getTranslations } from "next-intl/server";

import { APP_CONFIG } from "@/config/app-config";
import { LoginForm } from "@/features/auth/login-form";

export default async function LoginV2() {
  const t = await getTranslations("auth");
  return (
    <div className="w-full max-w-[400px] space-y-6">
      <div className="space-y-2 text-left">
        <p className="font-mono text-[11px] font-semibold uppercase tracking-[.14em] text-primary">
          {t("secureAccess")}
        </p>
        <h1 className="text-2xl font-bold tracking-tight">{t("title")}</h1>
        <p className="text-muted-foreground text-sm">{t("subtext")}</p>
      </div>
      <LoginForm />
      <p className="border-t pt-4 text-xs text-muted-foreground">{APP_CONFIG.copyright}</p>
    </div>
  );
}
