import { getTranslations } from "next-intl/server";

import { RoleGuard } from "@/components/syncro/role-guard";
import { AuditLogPage } from "@/features/audit-log/audit-log-page";

export default async function Page() {
  const t = await getTranslations("auditLog");
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGER_MAINTENANCE", "AUDITOR"]} title={t("title")}>
      <AuditLogPage />
    </RoleGuard>
  );
}
