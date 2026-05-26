"use client";

import { type FormEvent, useState } from "react";

import { useRouter } from "next/navigation";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { loginToSyncro } from "@/lib/api/syncro-api";
import { saveAuthSession } from "@/lib/auth/auth-client";

export function LoginForm() {
  const router = useRouter();
  const [loginIdentifier, setLoginIdentifier] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string>();
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(undefined);
    setIsSubmitting(true);
    try {
      const result = await loginToSyncro({ loginIdentifier, password });
      saveAuthSession(result.accessToken, result.expiresInSeconds, result.user);
      router.replace("/operations-overview");
      router.refresh();
    } catch {
      setError("Invalid login credentials. Check your local SUPER_ADMIN login and try again.");
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <form className="space-y-4 text-left" onSubmit={handleSubmit}>
      <div className="space-y-2">
        <label className="font-medium text-sm" htmlFor="loginIdentifier">
          Login
        </label>
        <Input
          id="loginIdentifier"
          name="loginIdentifier"
          type="email"
          autoComplete="username"
          value={loginIdentifier}
          onChange={(event) => setLoginIdentifier(event.target.value)}
          required
        />
      </div>
      <div className="space-y-2">
        <label className="font-medium text-sm" htmlFor="password">
          Password
        </label>
        <Input
          id="password"
          name="password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          required
        />
      </div>
      {error ? (
        <p
          className="rounded-lg border border-destructive/40 bg-destructive/10 px-3 py-2 text-destructive text-sm"
          role="alert"
        >
          {error}
        </p>
      ) : null}
      <Button className="w-full" type="submit" disabled={isSubmitting}>
        {isSubmitting ? "Signing in..." : "Sign in"}
      </Button>
    </form>
  );
}
