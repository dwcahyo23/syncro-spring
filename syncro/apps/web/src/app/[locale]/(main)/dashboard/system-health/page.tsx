import { getTranslations } from "next-intl/server";

import { RoleGuard } from "@/components/syncro/role-guard";
import { SystemHealthPage } from "@/features/system-health/components/system-health-page";

export default async function Page() {
  const t = await getTranslations("systemHealth");
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN"]} title={t("page.title")}>
      <SystemHealthPage />
    </RoleGuard>
  );
}
