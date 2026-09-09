import { getTranslations } from "next-intl/server";

import { RoleGuard } from "@/components/syncro/role-guard";
import { TelemetryDashboardPage } from "@/features/telemetry/components/telemetry-dashboard-page";

export default async function Page() {
  const t = await getTranslations("telemetry");
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGER_MAINTENANCE", "AUDITOR"]} title={t("title")}>
      <TelemetryDashboardPage />
    </RoleGuard>
  );
}
