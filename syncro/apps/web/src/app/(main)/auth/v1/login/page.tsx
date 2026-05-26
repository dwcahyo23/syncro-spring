import { LoginForm } from "@/features/auth/login-form";

export default function LoginV1() {
  return (
    <main className="flex min-h-dvh items-center justify-center bg-background p-8">
      <div className="w-full max-w-md space-y-6 text-center">
        <div className="space-y-2">
          <p className="font-medium text-muted-foreground text-sm">Syncro access</p>
          <h1 className="font-medium text-3xl">Sign in to Syncro</h1>
          <p className="text-muted-foreground text-sm">
            Use local SUPER_ADMIN credentials configured for this environment.
          </p>
        </div>
        <LoginForm />
      </div>
    </main>
  );
}
