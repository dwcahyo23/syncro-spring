import { redirect } from "next/navigation";

/** Superseded by the consolidated Master Data → Plants tab page (Setup tab). */
export default function OldSetupPage() {
  redirect("/master-data/plants?tab=setup");
}
