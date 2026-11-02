package com.skillmasterai.common;

import java.util.Locale;

/**
 * A byte count in the unit a person reads.
 *
 * <p>The shared kernel rather than an ingest type: a message in M6 and a message in M5 must agree,
 * and a module boundary may not be crossed just to format a number (ADR 0123).
 */
public final class Bytes {

    private Bytes() {
    }

    /** {@code 512 bytes}, {@code 1.5 KiB}, {@code 1.5 GiB}; whole values keep no decimals. */
    public static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " bytes";
        }
        if (bytes < 1024L * 1024L) {
            return wholeOrOneDecimal(bytes, 1024L, "KiB");
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return wholeOrOneDecimal(bytes, 1024L * 1024L, "MiB");
        }
        return wholeOrOneDecimal(bytes, 1024L * 1024L * 1024L, "GiB");
    }

    private static String wholeOrOneDecimal(long bytes, long unit, String suffix) {
        if (bytes % unit == 0) {
            return (bytes / unit) + " " + suffix;
        }
        return String.format(Locale.ROOT, "%.1f %s", bytes / (double) unit, suffix);
    }
}
