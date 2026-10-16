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
     * The credential was recognised and has been revoked — a deliberate act somebody took, and the
     * reason the answer differs from {@link #TOKEN_EXPIRED}: a revoked key cannot be renewed, only
     * replaced with a new one (ADR 0039 §决定 3).
     *
     * <p>Only ever sent to a caller that presented a credential this service has a row for. Guessing
     * one of those is not possible — the lookup is a sha256 of 256 bits — so this says nothing to
     * somebody who does not already hold a key. See {@code TokenValidator} for the boundary.
     */
    TOKEN_REVOKED("token_revoked"),
    /** The credential was recognised and its date has passed. Renewal is the way out (ADR 0039). */
    TOKEN_EXPIRED("token_expired"),
    /**
     * The credential was recognised but the account behind it is not active. Not about the credential
     * at all: renewing it, or issuing another, changes nothing until the account is restored.
     */
    ACCOUNT_SUSPENDED("account_suspended"),
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
    /**
     * No such key, or not the caller's. The same one answer for both, for the same reason
     * {@link #SKILL_NOT_FOUND} collapses four failures: a distinction here would confirm that a key
     * with that id exists (ADR 0038 §决定 2).
     */
    KEY_NOT_FOUND("key_not_found"),
    /**
     * No such login device, or not the caller's — one answer for both, for the reason
     * {@link #KEY_NOT_FOUND} has one and {@link #SKILL_NOT_FOUND} collapses four (ADR 0043 §接口):
     * a distinct answer for "that device exists but is not yours" would confirm that a device with
     * that id exists. Reached by anybody editing the address, and by the console after a row it was
     * showing has already been ended somewhere else.
     */
    SESSION_NOT_FOUND("session_not_found"),
    /** A scope outside the vocabulary, or none at all: a key that may do nothing reads as broken. */
    INVALID_SCOPE("invalid_scope"),
    /**
     * A date this deployment will not accept — either one that is not in the future (a key that would
     * never work, rather than a short-lived one) or one beyond the lifetime this deployment issues
     * (ADR 0042). The two are told apart by {@code details[].issue}
     * ({@code not_in_the_future} / {@code beyond_maximum}).
     */
    INVALID_EXPIRY("invalid_expiry"),
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
