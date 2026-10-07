package com.skillmasterai.common;

/**
 * The stable, machine-readable half of an error. Clients switch on these strings, so the wire
 * names are the contract and the enum constant names are not — renaming a constant must not
 * rename the wire value.
 *
 * <p>Note that {@link #SKILL_NOT_FOUND} covers both "no such skill" and "not yours". That is
 * deliberate and load-bearing: §4.2 requires a 404 rather than a 403 for a private skill the
 * caller may not see, because a 403 would confirm the skill exists. The message may differ; the
 * code and status may not.
 */
public enum ErrorCode {

    UNAUTHENTICATED("unauthenticated"),
    /**
     * A login attempt that did not authenticate. One code for all three ways it can fail — no such
     * phone, wrong password, suspended account — because answering them differently turns the
     * endpoint into an account-existence oracle. Same rule as {@link #SKILL_NOT_FOUND}.
     */
    INVALID_CREDENTIALS("invalid_credentials"),
    INSUFFICIENT_SCOPE("insufficient_scope"),
    /**
     * The request was understood and refused on grounds that are not about who the caller is —
     * on the browser plane, a missing or stale CSRF token. Distinct from {@link #INSUFFICIENT_SCOPE},
     * which is about a token that was accepted but carries too little.
     */
    FORBIDDEN("forbidden"),
    /** The caller has spent their budget for this window. Ships a {@code Retry-After} header. */
    TOO_MANY_REQUESTS("too_many_requests"),
    INVALID_REQUEST("invalid_request"),
    INVALID_UPLOAD("invalid_upload"),
    /**
     * The version name this submission declares already exists in this skill, with different
     * content. A version name is what makes {@code @1.2.3} mean one thing for ever, so it cannot be
     * reused — the author increments it. Distinct from {@link #INVALID_REQUEST}, which is how a
     * malformed version name is answered, and from the idempotent 200 a replay of identical content
     * gets.
     */
    VERSION_ALREADY_EXISTS("version_already_exists"),
    /**
     * The SMS code was wrong, expired, already used, or guessed too many times. Also the answer for
     * a phone with no account, since only a phone a code was sent to can reach this check at all.
     */
    VERIFICATION_CODE_INVALID("verification_code_invalid"),
    SKILL_NOT_FOUND("skill_not_found"),
    FILE_NOT_FOUND("file_not_found"),
    INTERNAL_ERROR("internal_error");

    private final String wireName;

    ErrorCode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
