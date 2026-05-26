"use client";

import { useSyncExternalStore } from "react";

import type { PlantScopeResponse } from "@/lib/api/syncro-api";

type PlantScopeState = {
  scope: PlantScopeResponse | null;
  activePlantId: string;
  loadError: boolean;
};

const ALL_PLANTS = "all";
const listeners = new Set<() => void>();
let state: PlantScopeState = { scope: null, activePlantId: ALL_PLANTS, loadError: false };

export function setPlantScope(scope: PlantScopeResponse | null) {
  state = { scope, activePlantId: scope?.defaultPlantId ?? ALL_PLANTS, loadError: false };
  emit();
}

export function setPlantScopeLoadError() {
  state = { scope: null, activePlantId: ALL_PLANTS, loadError: true };
  emit();
}

export function setActivePlantScope(activePlantId: string) {
  state = { ...state, activePlantId };
  emit();
}

export function resetPlantScope() {
  state = { scope: null, activePlantId: ALL_PLANTS, loadError: false };
  emit();
}

export function usePlantScope() {
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function getSnapshot() {
  return state;
}

function emit() {
  for (const listener of listeners) {
    listener();
  }
}
