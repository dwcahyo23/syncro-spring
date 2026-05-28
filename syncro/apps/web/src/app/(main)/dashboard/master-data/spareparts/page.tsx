import { RoleGuard } from "@/components/syncro/role-guard";
import { SparepartManagement } from "@/features/master-data/spareparts/sparepart-management";
import { SparepartTaxonomyManagement } from "@/features/master-data/spareparts/sparepart-taxonomy-management";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGE", "VIEWER"]} title="Spareparts">
      <div className="space-y-6">
        <SparepartManagement />
        <SparepartTaxonomyManagement />
      </div>
    </RoleGuard>
  );
}
