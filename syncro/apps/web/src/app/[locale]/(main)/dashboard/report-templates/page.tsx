import { getTranslations } from "next-intl/server";

import { ReportTemplatesPageContent } from "@/features/report-templates/report-templates-page-content";

export default async function Page() {
  const t = await getTranslations("reportTemplates.route");
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold">{t("title")}</h1>
        <p className="text-sm text-muted-foreground">{t("subtitle")}</p>
      </div>
      <ReportTemplatesPageContent />
    </div>
  );
}
