package com.skillmasterai.usecase;

import java.util.Locale;
import java.util.Optional;

/**
 * The exact-evidence half of the personal ranking (v59 P1, ADR 0180).
 *
 * <p><strong>S0 is the skill's own name; S1 is the sentence the caller wrote.</strong> Both are
 * exact evidence and both sit above any semantic score: a name that is the query, or a phrase the
 * caller explicitly said, must not lose to a neighbouring vector. They stay separate tiers because
 * the name is the platform's identity for the skill and the sentence is the caller's own.
 *
 * <p><strong>S1 uses the longest common substring, not a synonym table.</strong> A query and a
 * personal sentence hit when the shared run is at least three Han characters or five Latin
 * letters/digits. Longer runs rank higher. The substring is the whole rule; rewriting is S2's job.
 */
public final class PersonalTriggers {

    /** No evidence. */
    public static final int NONE = -1;
    /** The name, the platform's identity for the skill. */
    public static final int S0 = 0;
    /** The caller's exact phrase. */
    public static final int S1 = 1;
    /** The author's own when-to-use text, still recalled behind the personal layer. */
    public static final int S3 = 3;

    private PersonalTriggers() {
    }

    /** @param personal true for S1, false for S0; the search marker only marks the former */
    public record Match(int tier, double score, boolean personal) {
    }

    /**
     * @return the stronger of the exact signals, or empty when none fires
     */
    public static Optional<Match> exact(String query, String name, String note) {
        return exact(query, name, null, note);
    }

    /**
     * The name is the platform's identity and the title is the display identity; ADR 0180 puts
     * both in S0. The name wins inside that tier when both match, because the address is what
     * somebody would type.
     *
     * @return the stronger of the exact signals, or empty when none fires
     */
    public static Optional<Match> exact(String query, String name, String title, String note) {
        String q = normalize(query);
        if (q.isEmpty()) {
            return Optional.empty();
        }
        Optional<Match> identity = identity(q, name, 1000, 500);
        if (identity.isEmpty()) {
            identity = identity(q, title, 900, 400);
        }
        if (identity.isPresent()) {
            return identity;
        }
        if (note == null || note.isBlank()) {
            return Optional.empty();
        }
        String lcs = longestCommonSubstring(q, normalize(note));
        if (significant(lcs)) {
            return Optional.of(new Match(S1, lcs.codePointCount(0, lcs.length()), true));
        }
        return Optional.empty();
    }

    private static Optional<Match> identity(String q, String text, double exact, double contains) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        if (normalized.equals(q)) {
            return Optional.of(new Match(S0, exact, false));
        }
        if (q.codePointCount(0, q.length()) >= 2 && normalized.contains(q)) {
            return Optional.of(new Match(S0, contains, false));
        }
        return Optional.empty();
    }

    /**
     * The author's when-to-use sentence as S3 evidence (ADR 0180).
     *
     * <p>It is the same exact rule as S1 and deliberately not marked personal: it is the skill's
     * published text, not the caller's. Kept separate from the vector tier because ADR 0180 ranks
     * author content above generic semantic evidence.
     */
    public static Optional<Match> authorExact(String query, String whenToUse) {
        String q = normalize(query);
        if (q.isEmpty() || whenToUse == null || whenToUse.isBlank()) {
            return Optional.empty();
        }
        String lcs = longestCommonSubstring(q, normalize(whenToUse));
        if (significant(lcs)) {
            return Optional.of(new Match(S3, lcs.codePointCount(0, lcs.length()), false));
        }
        return Optional.empty();
    }

    /** The shared run's code-point length; 0 when there is none. */
    public static int longestCommonSubstringLength(String left, String right) {
        String run = longestCommonSubstring(left, right);
        return run.codePointCount(0, run.length());
    }

    /**
     * A run counts when it carries three Han characters or five Latin letters/digits. A mixed run
     * can qualify through either half: "Alice 那套" has one Latin word and two Han characters, and
     * the five-letter side is what makes it a real phrase rather than a chance overlap.
     */
    public static boolean significant(String run) {
        if (run == null || run.isEmpty()) {
            return false;
        }
        int han = 0;
        int lettersOrDigits = 0;
        for (int i = 0; i < run.length(); ) {
            int cp = run.codePointAt(i);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                han++;
            } else if (Character.isLetterOrDigit(cp)) {
                lettersOrDigits++;
            }
            i += Character.charCount(cp);
        }
        return han >= 3 || lettersOrDigits >= 5;
    }

    static String longestCommonSubstring(String left, String right) {
        int[] a = left.codePoints().toArray();
        int[] b = right.codePoints().toArray();
        int[] previous = new int[b.length + 1];
        int[] current = new int[b.length + 1];
        int bestLength = 0;
        int bestEnd = 0;
        for (int i = 1; i <= a.length; i++) {
            for (int j = 1; j <= b.length; j++) {
                current[j] = a[i - 1] == b[j - 1] ? previous[j - 1] + 1 : 0;
                if (current[j] > bestLength) {
                    bestLength = current[j];
                    bestEnd = i;
                }
            }
            int[] swap = previous;
            previous = current;
            current = swap;
            java.util.Arrays.fill(current, 0);
        }
        return new String(a, bestEnd - bestLength, bestLength);
    }

    private static String normalize(String text) {
        return text == null ? ""
                : text.replaceAll("[\\s\\p{Z}]+", " ").strip().toLowerCase(Locale.ROOT);
    }
}
