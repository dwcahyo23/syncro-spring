package com.syncro.maintenance.domain.workorder;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Application-level workorder rating value (FR-121/FR-124, story 10-8). Ratings are
 * immutable after submission — there is no update/delete endpoint; a duplicate
 * submission is rejected at the DB unique constraint level. {@code ratedUserId} is
 * null for a WORKORDER-type rating (bound to the workorder itself). Scores map
 * dimension code → 1..5; a rater may score a subset of the configured dimensions.
 */
public record WorkorderRating(
    UUID id,
    String workorderId,
    RatingType ratingType,
    UUID ratedUserId,
    UUID raterUserId,
    Instant createdAt,
    Map<String, Integer> scores) {
}
