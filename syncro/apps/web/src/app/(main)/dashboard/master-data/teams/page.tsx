import { redirect } from "next/navigation";

/** Superseded by the consolidated Master Data → Organization tab page (Teams tab). */
export default function OldTeamsPage() {
  redirect("/master-data/organization?tab=teams");
}
