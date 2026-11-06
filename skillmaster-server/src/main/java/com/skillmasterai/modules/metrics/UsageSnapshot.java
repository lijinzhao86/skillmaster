package com.skillmasterai.modules.metrics;

import java.time.LocalDate;

/**
 * One scope's aggregate for one skill, already reduced to what may leave the module.
 *
 * <p>Counts are returned as bucket floors, never as exact values: the floor is the privacy
 * decision — a small number of accounts or calls can be traced back to a person. Rendering a floor
 * as "1k+" is the reader's job; the floor is this module's answer.
 *
 * @param hasCalls           false when nobody has loaded this skill in this scope
 * @param callsBucketFloor   0 means 1–9; then 10 / 100 / 1000 / 10000 / 1000000
 * @param invokers30dBucketFloor 0 means 0–9 different accounts in the last 30 days
 * @param lastUsed           the latest day with a load in this scope, or null
 */
public record UsageSnapshot(
        boolean hasCalls,
        int callsBucketFloor,
        int invokers30dBucketFloor,
        LocalDate lastUsed) {

    /** The largest multiple-of-ten floor at or below {@code value}; 0 for anything under 10. */
    public static int floorOf(long value) {
        if (value < 10) {
            return 0;
        }
        if (value < 100) {
            return 10;
        }
        if (value < 1_000) {
            return 100;
        }
        if (value < 10_000) {
            return 1_000;
        }
        if (value < 1_000_000) {
            return 10_000;
        }
        return 1_000_000;
    }
}
