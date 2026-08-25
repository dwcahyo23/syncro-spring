import { RoleGuard } from "@/components/syncro/role-guard";
import { TelemetryDashboardPage } from "@/features/telemetry/components/telemetry-dashboard-page";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGER_MAINTENANCE", "AUDITOR"]} title="Telemetry">
      <TelemetryDashboardPage />
    </RoleGuard>
  );
}
