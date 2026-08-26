package com.syncro.maintenance.domain.workorder;

/**
 * Rating types (FR-121/FR-124, story 10-8). TECHNICIAN ratings are given by an in-scope
 * section leader to an executing technician; WORKORDER ratings are given by a
 * PRODUCTION_LEADER with plant access to the workorder itself (no rated user).
 */
public enum RatingType {
  TECHNICIAN,
  WORKORDER
}
