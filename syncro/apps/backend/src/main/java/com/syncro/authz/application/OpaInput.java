package com.syncro.authz.application;

/**
 * Authoritative OPA wire schema (Addendum A3) — full evaluation input posted to
 * {@code POST /v1/data/syncro/authz/{rule}}. Contains exactly subject/resource/action/context.
 */
public record OpaInput(OpaSubject subject, OpaResource resource, String action, OpaContext context) {
}
