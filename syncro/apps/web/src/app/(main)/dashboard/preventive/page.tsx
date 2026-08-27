import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { PreventiveProgramsPanel } from "@/features/preventive/components/preventive-programs-panel";
import { PreventiveScheduleList } from "@/features/preventive/components/preventive-schedule-list";

/**
 * Preventive maintenance route (story 11-1..11-3, FR-130..FR-134). Lives inside the
 * dashboard shell so it shares sidebar/header. Programs and schedules are two tabs of
 * the same menu, not separate nav items.
 */
export default function PreventivePage() {
  return (
    <Tabs defaultValue="programs" className="w-full">
      <div className="flex items-end justify-between gap-4 px-6 pt-6">
        <div>
          <h1 className="font-semibold text-xl">Preventive Maintenance</h1>
          <p className="text-muted-foreground text-sm">Define preventive programs and track due/overdue schedules.</p>
        </div>
        <TabsList>
          <TabsTrigger value="programs">Programs</TabsTrigger>
          <TabsTrigger value="schedules">Schedules</TabsTrigger>
        </TabsList>
      </div>
      <div className="p-6">
        <TabsContent value="programs">
          <PreventiveProgramsPanel />
        </TabsContent>
        <TabsContent value="schedules">
          <PreventiveScheduleList />
        </TabsContent>
      </div>
    </Tabs>
  );
}
