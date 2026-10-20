package com.skillmasterai.common;

import java.security.SecureRandom;

/**
 * A version row's id: eight characters of Crockford base32 (ADR 0063).
 *
 * <p><strong>Why eight and not a ULID.</strong> A draft's row id is the address a person copies out
 * of the console and pastes into a terminal, so it has to be short enough to read, type and compare
 * by eye. Eight characters of the same alphabet {@link Ulid} uses carry 40 bits of randomness —
 * about a one-in-a-trillion collision per row, and a collision is not silent in any case: the id is
 * a primary key, and {@code DraftRecorder} retries the whole submission with a fresh one.
 *
 * <p><strong>Not sortable, and that is the one property given up.</strong> A ULID's first ten
 * characters are a timestamp. Nothing orders versions by this id — {@code number} does that — so the
 * timestamp was never read, and keeping it would have made the id longer for nobody.
 *
 * <p>The alphabet is Crockford's: no {@code I}, {@code L}, {@code O} or {@code U}, so a code cannot
 * be misread as a word or as another code. Lowercase is rejected rather than folded, for the reason
 * {@link Ulid#isValid} rejects its aliases: an id that validates differently in two places is worse
 * than one that fails loudly.
 */
public final class ShortCode {

    private ShortCode() {
    }

    /** Crockford base32: no I, L, O or U. Shared shape with {@link Ulid}'s own alphabet. */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private static final int[] DECODED = new int[128];

    static {
        java.util.Arrays.fill(DECODED, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            DECODED[ALPHABET[i]] = i;
        }
    }

    public static final int LENGTH = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String generate() {
        char[] out = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }

    /** Exactly eight characters of the alphabet above — nothing more lenient than that. */
    public static boolean isValid(CharSequence candidate) {
        if (candidate == null || candidate.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < LENGTH; i++) {
            char c = candidate.charAt(i);
            if (c >= DECODED.length || DECODED[c] < 0) {
                return false;
            }
        }
        return true;
    }

    /** Either length a row id may have: a v7 short code, or a v6 ULID kept addressable. */
    public static boolean isValidRowId(CharSequence candidate) {
        return isValid(candidate) || Ulid.isValid(candidate);
    }
}
