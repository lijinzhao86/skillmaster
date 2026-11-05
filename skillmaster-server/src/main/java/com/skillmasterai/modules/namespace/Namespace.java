package com.skillmasterai.modules.namespace;

/**
 * A namespace: the scope a skill's name is unique within.
 *
 * <p>v1 gives every user exactly one, created at registration with {@code slug = handle} (§3.2).
 * That single rule is why {@code UNIQUE(namespace_id, name)} covers both "no duplicates in my own
 * space" and, later, "no duplicates in a team's" — without a migration.
 *
 * @param visibility one of {@code public}, {@code unlisted}, {@code private}. v1 only ever uses
 *                   {@code private}: there is no sharing or public discovery yet, and §3.2 says the
 *                   other two are reserved for v2 and must not be tested here.
 * @param official   whether skills in this namespace may be public (ADR 0146). Written only by
 *                   migrations/operators; registration leaves it false. A column rather than a
 *                   configured slug because the slug is the person's own handle.
 */
public record Namespace(String id, String slug, String title, String ownerUserId, String visibility,
        boolean official) {
}
