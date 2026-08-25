package com.syncro.org.application;

import java.util.UUID;

/**
 * Port implemented by the masterdata module (owner of machine_groups): whether a
 * section has at least one machine group containing at least one ACTIVE machine.
 * Machine groups have no status, so an "active machine group" is one with an
 * ACTIVE machine. Used to guard section deactivation.
 */
public interface SectionActiveMachineGroupReader {
  boolean hasActiveMachineGroup(UUID sectionId);
}
