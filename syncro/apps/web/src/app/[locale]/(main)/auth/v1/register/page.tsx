import { Link } from "@/i18n/navigation";

export default function RegisterV1() {
  return (
    <main className="flex min-h-dvh items-center justify-center bg-background p-8">
      <div className="w-full max-w-md space-y-4 text-center">
        <p className="font-medium text-muted-foreground text-sm">Syncro access</p>
        <h1 className="font-medium text-3xl">Registration shell placeholder</h1>
        <p className="text-muted-foreground text-sm">User provisioning and auth behavior arrive in Story 1.5.</p>
        <Link prefetch={false} className="text-sm underline underline-offset-4" href="/operations-overview">
          Continue to app shell
        </Link>
      </div>
    </main>
  );
}
