import { getTranslations } from "next-intl/server";

import { AlertListPageContent } from "@/features/alerts/alert-list-page-content";

export default async function Page() {
  const t = await getTranslations("alerts");
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold">{t("listPage.title")}</h1>
        <p className="text-sm text-muted-foreground">{t("listPage.subtitle")}</p>
      </div>
      <AlertListPageContent />
    </div>
  );
}
