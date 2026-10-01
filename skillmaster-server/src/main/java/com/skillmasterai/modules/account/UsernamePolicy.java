package com.skillmasterai.modules.account;

import com.skillmasterai.common.Text;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What a username is allowed to be (ADR 0013).
 *
 * <p>The rules are strict for a reason that is not tidiness: the username <em>is</em> the slug of
 * the owner's personal namespace, so it becomes the first segment of every address they publish.
 * Non-ASCII would be percent-encoded into those addresses; the characters that cannot survive a
 * request path are worse than ugly (see the known issues in the v1-hosting test plan). And it
 * cannot be changed afterwards, because the addresses it appears in are pinned and copied.
 */
public final class UsernamePolicy {

    /**
     * Lower-case ASCII, digits and hyphen; three to thirty characters, not starting with a hyphen.
     *
     * <p>Deliberately permissive about the rest: a trailing hyphen and a doubled one are accepted.
     * That is looseness rather than an oversight, and it is pinned by a test so that it stays a
     * decision — tightening it later is safe, loosening it is not, and only a pinned expectation
     * distinguishes the two from a bug.
     */
    private static final Pattern SHAPE = Pattern.compile("^[a-z0-9][a-z0-9-]{2,29}$");

    /**
     * The characters alone, without the length or position rules.
     *
     * <p>A second pattern so that the two failures can be told apart in the right order. Character
     * set first, because a two-character Chinese username is refused for its alphabet, and
     * answering "too short" sends its author off to type four more characters that are refused for
     * the same reason they were before.
     */
    private static final Pattern CHARACTERS = Pattern.compile("^[a-z0-9-]+$");

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 30;

    private UsernamePolicy() {
    }

    /**
     * @param handle the username as the request gave it, possibly null
     * @return an issue code naming what is wrong, or empty when the username is acceptable
     */
    public static Optional<String> problemWith(String handle) {
        if (Text.isBlank(handle)) {
            return Optional.of("required");
        }
        if (!CHARACTERS.matcher(handle).matches()) {
            return Optional.of("invalid_format");
        }
        if (handle.length() < MIN_LENGTH || handle.length() > MAX_LENGTH) {
            return Optional.of("invalid_length");
        }
        // By here only one rule is left — the shape check is kept as the single written statement
        // of what a username is, rather than restating its leading-hyphen rule as a third test.
        if (!SHAPE.matcher(handle).matches()) {
            return Optional.of("invalid_format");
        }
        return Optional.empty();
    }
}
