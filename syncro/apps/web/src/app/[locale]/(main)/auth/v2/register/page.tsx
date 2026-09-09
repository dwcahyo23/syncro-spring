import { getTranslations } from "next-intl/server";

import { APP_CONFIG } from "@/config/app-config";
import { Link } from "@/i18n/navigation";

export default async function RegisterV2() {
  const t = await getTranslations("auth");
  return (
    <main className="mx-auto flex w-full max-w-md flex-col justify-center space-y-6 p-8 text-center">
      <div className="space-y-2">
        <p className="font-medium text-muted-foreground text-sm">{t("eyebrow")}</p>
        <h1 className="font-medium text-3xl">{t("register.title")}</h1>
        <p className="text-muted-foreground text-sm">{t("register.subtext")}</p>
      </div>
      <Link prefetch={false} className="text-sm underline underline-offset-4" href="/operations-overview">
        {t("register.continueLink")}
      </Link>
      <p className="text-muted-foreground text-xs">{APP_CONFIG.copyright}</p>
    </main>
  );
}
