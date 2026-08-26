import { RatingsPageContent } from "@/features/workorders/components/ratings-page-content";

/**
 * Workorder ratings route (story 10-8, FR-121/FR-124). Server Component wrapper —
 * the interactive page lives in the client {@link RatingsPageContent} component.
 * The backend returns scope-filtered CLOSED workorders the user can rate, so no
 * client-side permission logic is needed here.
 */
export default function WorkordersRatingsPage() {
  return (
    <div className="space-y-4">
      <div>
        <h1 className="font-semibold text-xl">Workorder Ratings</h1>
        <p className="text-muted-foreground text-sm">Rate closed workorders and their executing technicians.</p>
      </div>
      <RatingsPageContent />
    </div>
  );
}
