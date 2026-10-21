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
     * The credential was recognised and has gone unused past its idle ruler.
     *
     * <p>Distinct from {@link #TOKEN_EXPIRED} because the next step differs: both mean logging in
     * again, but only the expired one is worth also answering with "or issue a key" — a credential
     * that ran out of calendar time is the shape of a job that needs a longer-lived one, while a
     * credential that ran out of use is a machine that was simply away for a while.
     *
     * <p>New in v4, and the first reason code a CLI login can produce and an API key cannot: a key's
     * lifetime is one date with no idleness rule behind it (ADR 0039 §决定 1).
     */
    TOKEN_IDLE("token_idle"),
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
    /**
     * No such sign-in code — or one that expired, was already spent, or whose PKCE verifier did not
     * match. Four failures, one answer, for the reason {@link #KEY_NOT_FOUND} has one and
     * {@link #SKILL_NOT_FOUND} collapses four: "you already spent that code" sounds more useful, and
     * it also tells whoever is holding a random string that the string was once real.
     *
     * <p>Reached by anybody editing the exchange request, and by nobody holding a code this service
     * issued — a caller who has one is the CLI that asked for it, and it redeems it once.
     */
    CODE_NOT_FOUND("code_not_found"),
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

    /** A submission without the version's name, or one that is only whitespace (ADR 0055). */
    DRAFT_NAME_REQUIRED("draft_name_required"),
    /** The version's name is longer than 40 code points (ADR 0055). */
    DRAFT_NAME_TOO_LONG("draft_name_too_long"),
    /** A draft name was changed after the version was published; the name freezes at publish. */
    DRAFT_NAME_IS_FROZEN("draft_name_is_frozen"),
    /** The note is longer than 200 code points. */
    NOTE_TOO_LONG("note_too_long"),
    /** A note was changed after the version was published; the note freezes at publish. */
    NOTE_IS_FROZEN("note_is_frozen"),
    /** The version is not in a state where this lifecycle action means anything. */
    INVALID_VERSION_STATE("invalid_version_state"),
    /**
     * The address pinned a version that was published and then taken down. 410 rather than 404:
     * the caller holds the pin, so "there was one and it is gone" is the honest answer, and it is
     * different from "no such version" (ADR 0058).
     */
    VERSION_REVOKED("version_revoked"),
    /** The release name is not a SemVer 2.0.0 string. */
    INVALID_RELEASE_NAME("invalid_release_name"),
    /** A draft with no version number was published without choosing one. */
    RELEASE_NAME_REQUIRED("release_name_required"),
    /** A version that already has a number was asked to take a different one. Numbers are assigned once. */
    VERSION_NAME_IS_ASSIGNED("version_name_is_assigned"),
    /** The whole skill is offline; every consumer read answers this until it is restored. */
    SKILL_OFFLINE("skill_offline"),

    /* ---- v7: draft file I/O ---- */

    /** A write without {@code If-Match}. 428: the baseline is what makes the write safe to retry. */
    IF_MATCH_REQUIRED("if_match_required"),
    /** The baseline moved since the caller read it. 409 with the digest that is there now. */
    DIGEST_MISMATCH("digest_mismatch"),
    /**
     * The content a write would produce already exists as another version of this skill. 409: the
     * unique constraint is the identity ADR 0005 makes content carry, and two rows with one digest
     * would make "which version is this" unanswerable. The detail always names the state, and names
     * the pin only when that state is visible to the caller — a `write` holder cannot open a
     * discarded or delisted row, so naming its id would be an address the next request refuses.
     */
    CONTENT_ALREADY_EXISTS("content_already_exists"),
    /** {@code mv} / {@code cp} to a path this version already has. */
    FILE_ALREADY_EXISTS("file_already_exists"),
    /** {@code rm} / {@code mv} of the root {@code SKILL.md}; a skill cannot be without one. */
    SKILL_MD_CANNOT_BE_DELETED("skill_md_cannot_be_deleted"),
    /** A delete that would leave the version with no files at all. */
    LAST_FILE_CANNOT_BE_DELETED("last_file_cannot_be_deleted"),
    /** A write aimed at a row that is not a draft. Published rows are frozen (v7 TD §行的可变性). */
    VERSION_IS_NOT_DRAFT("version_is_not_draft"),
    /** {@code grep} is a draft-only operation; a published version is searched with {@code cat | grep}. */
    GREP_REQUIRES_DRAFT("grep_requires_draft"),
    /** A relpath that is absolute, escapes the root, or uses a backslash. */
    INVALID_PATH("invalid_path"),
    /**
     * The name still belongs to a soft-deleted skill (ADR 0050).
     *
     * <p>A named conflict rather than a generic 400 because the only way out — restoring — is not
     * part of this request, and a client that cannot see the reason cannot offer it (ADR 0071).
     */
    SKILL_DELETED("skill_deleted"),
    /** Creating a skill whose name already belongs to a live one. */
    SKILL_ALREADY_EXISTS("skill_already_exists"),
    /** A write, move or copy aimed at a path the platform reserves for itself (ADR 0069). */
    RESERVED_PATH("reserved_path"),

    INTERNAL_ERROR("internal_error");

    private final String wireName;

    ErrorCode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
