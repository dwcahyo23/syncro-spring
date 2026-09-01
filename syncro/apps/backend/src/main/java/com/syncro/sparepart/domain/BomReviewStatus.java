package com.syncro.sparepart.domain;

/**
 * BOM master review lifecycle (Story 18-1). New spareparts start {@code PENDING_REVIEW};
 * a privileged review transitions them once to {@code ACTIVE} (approved) or
 * {@code REJECTED} (with a stored reason). Both terminal states are final for this story.
 */
public enum BomReviewStatus {
  PENDING_REVIEW,
  ACTIVE,
  REJECTED
}
