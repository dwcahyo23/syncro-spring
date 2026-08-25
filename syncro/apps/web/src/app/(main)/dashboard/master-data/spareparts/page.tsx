import { RoleGuard } from "@/components/syncro/role-guard";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { SparepartManagement } from "@/features/master-data/spareparts/sparepart-management";
import { SparepartTaxonomyManagement } from "@/features/master-data/spareparts/sparepart-taxonomy-management";

export default function Page() {
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGER_MAINTENANCE", "AUDITOR"]} title="Spareparts">
      <div className="space-y-6">
        <Tabs defaultValue="spareparts" className="w-full">
          <TabsList className="mb-4">
            <TabsTrigger value="spareparts">Spareparts</TabsTrigger>
            <TabsTrigger value="taxonomy">Category</TabsTrigger>
          </TabsList>
          <TabsContent value="spareparts">
            <SparepartManagement />
          </TabsContent>
          <TabsContent value="taxonomy">
            <SparepartTaxonomyManagement />
          </TabsContent>
        </Tabs>
      </div>
    </RoleGuard>
  );
}
