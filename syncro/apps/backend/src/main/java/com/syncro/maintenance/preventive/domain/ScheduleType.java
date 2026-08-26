package com.syncro.maintenance.preventive.domain;

/**
 * Preventive program schedule type (FR-130, AD-12, story 11-1). MONTHLY generates
 * an anchor per month; ANNUAL generates one per year. No daily/weekly/hourly for now.
 */
public enum ScheduleType {
  MONTHLY,
  ANNUAL
}