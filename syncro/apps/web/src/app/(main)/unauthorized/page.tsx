import Link from "next/link";

export default function UnauthorizedPage() {
  return (
    <main className="flex min-h-dvh flex-col items-center justify-center bg-background px-4 py-12 text-center sm:px-6 lg:px-8">
      <div className="mx-auto max-w-md space-y-4">
        <p className="font-medium text-muted-foreground text-sm">Syncro access</p>
        <h1 className="font-bold text-3xl tracking-tight sm:text-4xl">Unauthorized shell placeholder</h1>
        <p className="text-muted-foreground">Role enforcement and access decisions arrive in Story 1.6.</p>
        <Link href="/operations-overview" className="text-sm underline underline-offset-4" prefetch={false}>
          Go to app shell
        </Link>
      </div>
    </main>
  );
}
