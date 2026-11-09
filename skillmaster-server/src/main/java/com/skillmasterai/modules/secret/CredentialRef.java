package com.skillmasterai.modules.secret;

/**
 * One credential as its owner sees it: the label, the dial, and the timestamps — never the value.
 *
 * @param id          {@code service_credential.id}
 * @param ownerUserId who the value belongs to. Carried because every write has to answer "is this
 *                    the caller's" before it does anything, and reading it from the same row avoids
 *                    a second query that could disagree
 * @param name        the human label; unique per owner
 * @param perDayLimit how many leases this credential may serve per day
 * @param createdAt   RFC3339 UTC
 * @param rotatedAt   when the value last changed, or null when it never has
 * @param lastUsedAt  when a lease last used it, or null
 */
public record CredentialRef(String id, String ownerUserId, String name, int perDayLimit,
        String createdAt, String rotatedAt, String lastUsedAt) {
}
