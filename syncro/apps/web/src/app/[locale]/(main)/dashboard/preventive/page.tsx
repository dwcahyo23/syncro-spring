import { getTranslations } from "next-intl/server";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { PreventiveCategoryManagement } from "@/features/preventive/components/preventive-category-management";
import { PreventiveProgramsPanel } from "@/features/preventive/components/preventive-programs-panel";
import { PreventiveScheduleList } from "@/features/preventive/components/preventive-schedule-list";

/**
 * Preventive maintenance route (story 11-1..11-3, FR-130..FR-134). Lives inside the
 * dashboard shell so it shares sidebar/header. Programs, schedules and category master
 * data are tabs of the same menu, not separate nav items.
 */
export default async function PreventivePage() {
  const t = await getTranslations("preventive");
  return (
    <Tabs defaultValue="programs" className="w-full">
      <div className="flex items-end justify-between gap-4 px-6 pt-6">
        <div>
          <h1 className="font-semibold text-xl">{t("page.title")}</h1>
          <p className="text-muted-foreground text-sm">{t("page.subtitle")}</p>
        </div>
        <TabsList>
          <TabsTrigger value="programs">{t("page.tabPrograms")}</TabsTrigger>
          <TabsTrigger value="schedules">{t("page.tabSchedules")}</TabsTrigger>
          <TabsTrigger value="categories">{t("page.tabCategories")}</TabsTrigger>
        </TabsList>
      </div>
      <div className="p-6">
        <TabsContent value="programs">
          <PreventiveProgramsPanel />
        </TabsContent>
        <TabsContent value="schedules">
          <PreventiveScheduleList />
        </TabsContent>
        <TabsContent value="categories">
          <PreventiveCategoryManagement />
        </TabsContent>
      </div>
    </Tabs>
  );
}
