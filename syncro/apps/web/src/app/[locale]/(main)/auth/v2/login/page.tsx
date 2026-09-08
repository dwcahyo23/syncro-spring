import { APP_CONFIG } from "@/config/app-config";
import { LoginForm } from "@/features/auth/login-form";

export default function LoginV2() {
  return (
    <div className="w-full max-w-[400px] space-y-6">
      <div className="space-y-2 text-left">
        <p className="font-mono text-[11px] font-semibold uppercase tracking-[.14em] text-primary">Secure access</p>
        <h1 className="text-2xl font-bold tracking-tight">Sign in to Syncro</h1>
        <p className="text-muted-foreground text-sm">
          Use local SUPER_ADMIN credentials configured for this environment.
        </p>
      </div>
      <LoginForm />
      <p className="border-t pt-4 text-xs text-muted-foreground">{APP_CONFIG.copyright}</p>
    </div>
  );
}
