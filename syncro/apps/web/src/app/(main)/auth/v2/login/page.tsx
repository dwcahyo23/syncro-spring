import { APP_CONFIG } from "@/config/app-config";
import { LoginForm } from "@/features/auth/login-form";

export default function LoginV2() {
  return (
    <main className="mx-auto flex w-full max-w-md flex-col justify-center space-y-6 p-8 text-center">
      <div className="space-y-2">
        <p className="font-medium text-muted-foreground text-sm">Syncro access</p>
        <h1 className="font-medium text-3xl">Sign in to Syncro</h1>
        <p className="text-muted-foreground text-sm">
          Use local SUPER_ADMIN credentials configured for this environment.
        </p>
      </div>
      <LoginForm />
      <p className="text-muted-foreground text-xs">{APP_CONFIG.copyright}</p>
    </main>
  );
}
