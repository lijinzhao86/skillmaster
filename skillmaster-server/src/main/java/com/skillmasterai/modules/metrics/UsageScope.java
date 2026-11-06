package com.skillmasterai.modules.metrics;

import java.util.Locale;

/**
 * Why a load is being measured — not who may see the measurement.
 *
 * <p>The two are deliberately separate: visibility is a display decision taken by whoever reads the
 * numbers, while the scope decides which series a load belongs to and therefore which retention and
 * privacy rules apply. v49 enables {@link #PUBLIC} only; opening an owner-facing scope is a new ADR
 * (ADR 0153), not a new table.
 */
public enum UsageScope {
    PUBLIC;

    /** The value stored in `skill_usage_day.scope` and `skill_usage_total.scope`. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static UsageScope fromWire(String value) {
        for (UsageScope scope : values()) {
            if (scope.wire().equals(value)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("unknown usage scope: " + value);
    }
}
