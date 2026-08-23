package com.syncro.auth.application;

/**
 * Raised when the authenticated user lacks the required job-scope level for a procurement
 * readiness mutation (FR-088). Carries only the level name — safe to surface verbatim.
 */
public class JobScopeForbiddenException extends RuntimeException {

  private final String requiredLevel;

  public JobScopeForbiddenException(String requiredLevel) {
    super("This action requires job scope " + requiredLevel + " or above.");
    this.requiredLevel = requiredLevel;
  }

  public String getRequiredLevel() {
    return requiredLevel;
  }
}
