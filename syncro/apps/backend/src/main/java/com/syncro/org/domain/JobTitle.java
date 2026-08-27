package com.syncro.org.domain;

import java.util.UUID;

/**
 * Job title master entry (user-master reference data). {@code code} is unique and
 * human-meaningful (e.g. {@code MECHANIC}, {@code SUPERVISOR}); {@code name} is the
 * display label. Optional {@code description} holds detail only.
 */
public record JobTitle(UUID id, String code, String name, String description) {
}
