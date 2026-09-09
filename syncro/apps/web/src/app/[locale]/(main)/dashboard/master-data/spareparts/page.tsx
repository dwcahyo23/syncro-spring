import { getTranslations } from "next-intl/server";

import { RoleGuard } from "@/components/syncro/role-guard";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { SparepartManagement } from "@/features/master-data/spareparts/sparepart-management";
import { SparepartTaxonomyManagement } from "@/features/master-data/spareparts/sparepart-taxonomy-management";

export default async function Page() {
  const t = await getTranslations("masterData.tabs");
  return (
    <RoleGuard allowedRoles={["SUPER_ADMIN", "MANAGER_MAINTENANCE", "AUDITOR"]} title={t("sparepartsPage")}>
      <div className="space-y-6">
        <Tabs defaultValue="spareparts" className="w-full">
          <TabsList className="mb-4">
            <TabsTrigger value="spareparts">{t("spareparts")}</TabsTrigger>
            <TabsTrigger value="taxonomy">{t("taxonomy")}</TabsTrigger>
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
