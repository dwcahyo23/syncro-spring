"use client";

import { useEffect } from "react";

import { PlantScopeSelector } from "@/components/syncro/plant-scope-selector";
import { fetchPlantScope } from "@/lib/api/syncro-api";
import { AUTH_TOKEN_COOKIE } from "@/lib/auth/auth-session";
import { useAuthUser } from "@/lib/auth/use-auth-user";
import { getClientCookie } from "@/lib/cookie.client";

import { resetPlantScope, setActivePlantScope, setPlantScope, usePlantScope } from "./plant-scope-store";

export function PlantScopeShell() {
  const authUser = useAuthUser();
  const { activePlantId, scope } = usePlantScope();

  useEffect(() => {
    resetPlantScope();

    const token = getClientCookie(AUTH_TOKEN_COOKIE);
    if (!authUser || !token) {
      return;
    }

    let active = true;

    fetchPlantScope(token)
      .then((plantScope) => {
        if (active) {
          setPlantScope(plantScope);
        }
      })
      .catch(() => {
        if (active) {
          setPlantScope({
            mode: "EMPTY",
            availablePlants: [],
            defaultPlantId: null,
            emptyReason: "NO_PLANTS_ASSIGNED",
          });
        }
      });

    return () => {
      active = false;
    };
  }, [authUser]);

  if (!authUser || !scope) {
    return null;
  }

  return (
    <PlantScopeSelector
      activePlantId={activePlantId}
      availablePlants={scope.availablePlants}
      className="hidden md:inline-flex"
      emptyReason={scope.emptyReason}
      isSuperAdmin={authUser.applicationRole === "SUPER_ADMIN"}
      onSelect={setActivePlantScope}
    />
  );
}
