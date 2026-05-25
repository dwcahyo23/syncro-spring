import Link from "next/link";

import { APP_CONFIG } from "@/config/app-config";

export default function RegisterV2() {
  return (
    <main className="mx-auto flex w-full max-w-md flex-col justify-center space-y-6 p-8 text-center">
      <div className="space-y-2">
        <p className="font-medium text-muted-foreground text-sm">Syncro access</p>
        <h1 className="font-medium text-3xl">Registration shell placeholder</h1>
        <p className="text-muted-foreground text-sm">User provisioning and auth behavior arrive in Story 1.5.</p>
      </div>
      <Link prefetch={false} className="text-sm underline underline-offset-4" href="/operations-overview">
        Continue to app shell
      </Link>
      <p className="text-muted-foreground text-xs">{APP_CONFIG.copyright}</p>
    </main>
  );
}
