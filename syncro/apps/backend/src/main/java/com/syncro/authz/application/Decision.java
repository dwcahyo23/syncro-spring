package com.syncro.authz.application;

/**
 * Result of an authorization evaluation — never thrown, never null.
 *
 * @param allowed    policy outcome; degraded allowlist matches also yield true
 * @param degraded   true when the decision came from the degraded-mode allowlist because
 *                   OPA was unreachable, not from an actual policy evaluation
 * @param decisionId OPA decision id for audit correlation; generated UUID when OPA did
 *                   not return one, null on degraded allows and denies
 */
public record Decision(boolean allowed, boolean degraded, String decisionId) {
}
