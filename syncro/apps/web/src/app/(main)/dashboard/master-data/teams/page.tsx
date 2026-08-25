import { RoleGuard } from "@/components/syncro/role-guard";
import { TeamManagement } from "@/features/master-data/teams/team-management";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGE"]} title="Cross-Plant Teams">
      <TeamManagement />
    </RoleGuard>
  );
}
