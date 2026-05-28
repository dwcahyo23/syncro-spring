import { RoleGuard } from "@/components/syncro/role-guard";
import { SparepartTaxonomyManagement } from "@/features/master-data/spareparts/sparepart-taxonomy-management";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGE", "VIEWER"]} title="Spareparts">
      <SparepartTaxonomyManagement />
    </RoleGuard>
  );
}
