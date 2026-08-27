import { redirect } from "next/navigation";

/** Superseded by the consolidated Master Data → Organization tab page (Responsibility tab). */
export default function OldResponsibilitiesPage() {
  redirect("/master-data/organization?tab=responsibility");
}
