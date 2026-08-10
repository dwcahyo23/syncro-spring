import { RoleGuard } from "@/components/syncro/role-guard";
import { TelemetryDashboardPage } from "@/features/telemetry/components/telemetry-dashboard-page";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGE", "VIEWER"]} title="Telemetry">
      <TelemetryDashboardPage />
    </RoleGuard>
  );
}
