import { redirect } from "next/navigation";

/** Superseded by the consolidated Master Data → Organization tab page (Plants tab). */
export default function OldSetupPage() {
  redirect("/master-data/organization?tab=plants");
}
