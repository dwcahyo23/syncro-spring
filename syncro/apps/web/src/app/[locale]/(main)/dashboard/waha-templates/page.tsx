import { getTranslations } from "next-intl/server";

import { WahaTemplatePageContent } from "@/features/waha-templates/waha-template-page-content";

export default async function Page() {
  const t = await getTranslations("wahaTemplates.route");
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold">{t("title")}</h1>
        <p className="text-sm text-muted-foreground">{t("subtitle")}</p>
      </div>
      <WahaTemplatePageContent />
    </div>
  );
}
