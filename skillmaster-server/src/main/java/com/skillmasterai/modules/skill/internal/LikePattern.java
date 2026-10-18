package com.skillmasterai.modules.skill.internal;

/**
 * Turns caller text into a {@code LIKE} pattern that matches it literally.
 *
 * <p>Without this, a query of {@code %} matches every skill and {@code _} matches any single
 * character — the caller's text would be read as a pattern language rather than as text. That is a
 * correctness bug, not an injection: the value still travels as a bound parameter, so nothing can
 * escape the string. It is the <em>meaning</em> of the characters that has to be pinned down.
 *
 * <p>It lives beside the SQL that consumes it rather than beside the code that decides what to
 * search for. The pattern and the {@code ESCAPE} clause are one idea in two places, and a module
 * boundary between them is a chance for them to disagree — which nothing would notice, because a
 * wrong escape produces plausible results rather than an error.
 */
final class LikePattern {

    /** Reserved in {@code LIKE}: each is prefixed with itself so the caller's character survives. */
    private static final char ESCAPE_CHAR = '\\';

    private LikePattern() {
    }

    /** @return a containing pattern, or null when there is no text to search for */
    static String containing(String query) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        StringBuilder pattern = new StringBuilder(query.length() + 8).append('%');
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == ESCAPE_CHAR || c == '%' || c == '_') {
                pattern.append(ESCAPE_CHAR);
            }
            pattern.append(c);
        }
        return pattern.append('%').toString();
    }

    /** Appended to every {@code LIKE} whose pattern came from {@link #containing}. */
    static final String ESCAPE_CLAUSE = "ESCAPE '\\'";
}
