package com.syncro.maintenance.infrastructure.db;

import java.util.UUID;

/**
 * One per-dimension average rating (story 14-2, FR-174): TECHNICIAN-type ratings joined
 * to workorders in scope, averaged per (rated user, dimension).
 */
public record RatingAverageRow(
    UUID ratedUserId,
    UUID dimensionId,
    Double averageScore) {
}
