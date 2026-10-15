package com.skillmasterai.modules.token;

/**
 * An API key as the rest of the system sees one — which is everything except the key.
 *
 * <p>The secret is not a field here and there is no factory that could put one in: it exists in one
 * response body, once, at the moment it is issued ({@link IssuedApiKey}), and after that only its
 * sha256 does. The console addresses a key by {@link #id}, because the value it would rather use is
 * the one nothing can read back.
 *
 * <p>{@code scope} is the space-separated spelling the wire uses, the same vocabulary as
 * {@code access_token.scope}, and it is a <strong>ceiling rather than a grant</strong>: what a
 * request may do is this intersected with what the account may do at that moment.
 *
 * @param id        ULID. The handle — opaque, and stable across a renewal
 * @param name      what the person called it, e.g. {@code laptop} or {@code ci}. Identity, one line
 * @param note      why it was issued, in their own words. Null when nothing was written, which is a
 *                  real answer and not a gap to be filled in
 * @param scope     space-separated scopes
 * @param expiresAt RFC3339 UTC, or null for a key that does not expire (ADR 0039 §决定 1)
 * @param lastUsedAt RFC3339 UTC, or null if it has never been used. Written through a staleness
 *                  window, so it can trail the truth by up to that window (§开放问题 Q1)
 * @param revokedAt RFC3339 UTC, or null while the key works. A timestamp rather than a flag
 *                  because the listing has to answer "when did it stop working"
 * @param createdAt RFC3339 UTC
 */
public record ApiKey(
        String id,
        String name,
        String note,
        String scope,
        String expiresAt,
        String lastUsedAt,
        String revokedAt,
        String createdAt) {
}
