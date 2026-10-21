package com.skillmasterai.usecase;

import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import java.util.regex.Pattern;

/**
 * The shape rules for a version's name and note (ADR 0055).
 *
 * <p>Both are single-line labels: whitespace is trimmed and folded, control characters are refused,
 * and the limits are counted in Unicode code points rather than UTF-16 units, so an emoji costs
 * what a reader would say it costs. The two entry points into a submission share these checks, which
 * is why they are here rather than in either use case.
 */
final class VersionLabels {

    private static final int NAME_MAX = 40;
    private static final int NOTE_MAX = 200;

    /** Control characters other than the whitespace we are about to fold. */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\t\\n\\r]]");

    private VersionLabels() {
    }

    /**
     * @throws RequestException {@code draft_name_required} when absent or blank,
     *                          {@code draft_name_too_long} past 40 code points,
     *                          {@code invalid_request} for a control character
     */
    static String name(String raw) {
        String text = collapse(raw);
        if (text.isEmpty()) {
            throw new RequestException(ErrorCode.DRAFT_NAME_REQUIRED,
                    "a version needs a name of 1-40 characters; it is the author's label for it");
        }
        requireNoControl(text);
        if (text.codePointCount(0, text.length()) > NAME_MAX) {
            throw new RequestException(ErrorCode.DRAFT_NAME_TOO_LONG,
                    "a version's name can be at most " + NAME_MAX + " characters");
        }
        return text;
    }

    /**
     * @return the note, or null when it is empty — an absent note is {@code null} on the wire, not
     *         an empty string
     * @throws RequestException {@code note_too_long} past 200 code points, {@code invalid_request}
     *                          for a control character
     */
    static String note(String raw) {
        String text = collapse(raw);
        if (text.isEmpty()) {
            return null;
        }
        requireNoControl(text);
        if (text.codePointCount(0, text.length()) > NOTE_MAX) {
            throw new RequestException(ErrorCode.NOTE_TOO_LONG,
                    "a version's note can be at most " + NOTE_MAX + " characters");
        }
        return text;
    }

    /**
     * The optional take-down reason: 0–200 code points, folded to one line (ADR 0059).
     *
     * @return the reason, or null when it is empty — a take-down needs no explanation
     * @throws RequestException {@code invalid_request} past 200 code points or for a control
     *         character; it has no code of its own because the TD gives it none
     */
    static String reason(String raw) {
        String text = collapse(raw);
        if (text.isEmpty()) {
            return null;
        }
        requireNoControl(text);
        if (text.codePointCount(0, text.length()) > NOTE_MAX) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "a take-down reason can be at most " + NOTE_MAX + " characters");
        }
        return text;
    }

    /**
     * The version's name: the request's value if it gave one, else the package's (ADR 0069).
     *
     * <p><strong>Blank counts as absent on both sides.</strong> The request cannot say "clear the
     * name" — a version must have one — so an empty string is just an unset field, which is also
     * what a CLI gets when it omits the flag. The package's value is a fallback, never an override.
     *
     * @throws RequestException {@code draft_name_required} when neither source has a name
     */
    static String nameOrPackaged(String explicit, String packaged) {
        return name(explicit == null || explicit.isBlank() ? packaged : explicit);
    }

    /**
     * The version's note, under the same precedence as the name; empty means "no note".
     *
     * @throws RequestException {@code note_too_long} past 200 code points
     */
    static String noteOrPackaged(String explicit, String packaged) {
        return note(explicit == null || explicit.isBlank() ? packaged : explicit);
    }

    private static String collapse(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("\\s+", " ").trim();
    }

    private static void requireNoControl(String text) {
        if (CONTROL.matcher(text).find()) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "a version's name and note are single lines and cannot contain control characters");
        }
    }
}
