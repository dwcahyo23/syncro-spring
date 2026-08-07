import { RoleGuard } from "@/components/syncro/role-guard";
import { AuditLogPage } from "@/features/audit-log/audit-log-page";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGE", "VIEWER"]} title="Audit Log">
      <AuditLogPage />
    </RoleGuard>
  );
}
