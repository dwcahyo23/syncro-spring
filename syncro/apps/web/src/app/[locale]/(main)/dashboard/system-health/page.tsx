import { RoleGuard } from "@/components/syncro/role-guard";
import { SystemHealthPage } from "@/features/system-health/components/system-health-page";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN"]} title="System Health">
      <SystemHealthPage />
    </RoleGuard>
  );
}
