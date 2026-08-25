package com.syncro.authz.application;

/**
 * Authoritative OPA wire schema (Addendum A3) — correlation context.
 */
public record OpaContext(String traceId) {
}
