import { PreventiveProgramsPanel } from "@/features/preventive/components/preventive-programs-panel";
import { PreventiveScheduleList } from "@/features/preventive/components/preventive-schedule-list";

/**
 * Preventive maintenance route (story 11-1, FR-130/FR-131). Server Component wrapper —
 * the interactive panels live in the client components. The backend returns scope-filtered
 * programs and schedules, so no client-side permission logic is needed here.
 */
export default function PreventivePage() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="font-semibold text-xl">Preventive Maintenance</h1>
        <p className="text-muted-foreground text-sm">Define preventive programs and track due/overdue schedules.</p>
      </div>
      <div className="grid gap-6 lg:grid-cols-2">
        <div className="space-y-3">
          <h2 className="font-medium text-base">Programs</h2>
          <PreventiveProgramsPanel />
        </div>
        <div className="space-y-3">
          <h2 className="font-medium text-base">Due / Overdue Schedules</h2>
          <PreventiveScheduleList />
        </div>
      </div>
    </div>
  );
}
