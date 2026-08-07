import { RoleGuard } from "@/components/syncro/role-guard";
import { SetupCompletenessPage } from "@/features/setup/setup-completeness-page";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGE", "VIEWER"]} title="Setup">
      <SetupCompletenessPage />
    </RoleGuard>
  );
}
