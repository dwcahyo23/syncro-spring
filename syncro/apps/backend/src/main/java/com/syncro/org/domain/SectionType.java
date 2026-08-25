package com.syncro.org.domain;

/**
 * Section type within a plant (AD-2). Sections are org containers — a machine
 * group belongs to exactly one section and section leadership is derived from
 * machine responsibilities; a section is never a scoping dimension for reads.
 */
public enum SectionType {
  MACHINERY,
  UTILITY,
  WORKSHOP
}
