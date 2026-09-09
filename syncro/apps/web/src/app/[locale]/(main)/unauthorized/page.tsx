import { getTranslations } from "next-intl/server";

import { Link } from "@/i18n/navigation";

export default async function UnauthorizedPage() {
  const t = await getTranslations("unauthorized");
  return (
    <main className="flex min-h-dvh flex-col items-center justify-center bg-background px-4 py-12 text-center sm:px-6 lg:px-8">
      <div className="mx-auto max-w-md space-y-4">
        <p className="font-medium text-muted-foreground text-sm">{t("eyebrow")}</p>
        <h1 className="font-bold text-3xl tracking-tight sm:text-4xl">{t("title")}</h1>
        <p className="text-muted-foreground">{t("description")}</p>
        <Link href="/operations-overview" className="text-sm underline underline-offset-4" prefetch={false}>
          {t("goToShell")}
        </Link>
      </div>
    </main>
  );
}
