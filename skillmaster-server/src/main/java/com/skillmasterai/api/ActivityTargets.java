package com.skillmasterai.api;

/** The one bound on a stored activity target (TD §数据模型: 300 characters). */
final class ActivityTargets {

    private static final int MAX = 300;

    private ActivityTargets() {
    }

    /**
     * Cuts without splitting a surrogate pair.
     *
     * <p>{@code String.length()} counts UTF-16 units, so a cut at 300 can land between an emoji's
     * two halves. PostgreSQL refuses the resulting lone surrogate, and the whole event write would
     * fail — a search that worked returning no history row. One unit shorter is always legal.
     */
    static String cut(String value) {
        if (value.length() <= MAX) {
            return value;
        }
        int end = MAX;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
