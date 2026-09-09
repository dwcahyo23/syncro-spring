import { getTranslations } from "next-intl/server";

import { Link } from "@/i18n/navigation";

export default async function RegisterV1() {
  const t = await getTranslations("auth");
  return (
    <main className="flex min-h-dvh items-center justify-center bg-background p-8">
      <div className="w-full max-w-md space-y-4 text-center">
        <p className="font-medium text-muted-foreground text-sm">{t("eyebrow")}</p>
        <h1 className="font-medium text-3xl">{t("register.title")}</h1>
        <p className="text-muted-foreground text-sm">{t("register.subtext")}</p>
        <Link prefetch={false} className="text-sm underline underline-offset-4" href="/operations-overview">
          {t("register.continueLink")}
        </Link>
      </div>
    </main>
  );
}
