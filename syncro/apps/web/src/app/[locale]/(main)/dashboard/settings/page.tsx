import { getTranslations } from "next-intl/server";

import { ModulePlaceholder } from "@/components/syncro/module-placeholder";

export default async function Page() {
  const t = await getTranslations("settings.page");
  return (
    <ModulePlaceholder
      title={t("title")}
      description={t("description")}
      sections={[t("sections.profile"), t("sections.theme"), t("sections.auth")]}
    />
  );
}
