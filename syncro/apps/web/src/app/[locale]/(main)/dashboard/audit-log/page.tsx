import { RoleGuard } from "@/components/syncro/role-guard";
import { AuditLogPage } from "@/features/audit-log/audit-log-page";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGER_MAINTENANCE", "AUDITOR"]} title="Audit Log">
      <AuditLogPage />
    </RoleGuard>
  );
}
