import { RoleGuard } from "@/components/syncro/role-guard";
import { SetupCompletenessPage } from "@/features/setup/setup-completeness-page";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGER_MAINTENANCE", "AUDITOR"]} title="Setup">
      <SetupCompletenessPage />
    </RoleGuard>
  );
}
