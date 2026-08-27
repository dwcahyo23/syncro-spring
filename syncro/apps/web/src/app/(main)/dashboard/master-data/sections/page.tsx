import { redirect } from "next/navigation";

/** Superseded by the consolidated Master Data → Organization tab page (Sections tab). */
export default function OldSectionsPage() {
  redirect("/master-data/organization?tab=sections");
}
