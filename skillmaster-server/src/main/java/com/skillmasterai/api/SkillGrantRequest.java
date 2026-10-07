package com.skillmasterai.api;

/**
 * The body of a share: who, and how much (ADR 0034).
 *
 * @param handle the account, as a person types it. A username rather than a user id, because the id
 *               is a ULID nobody knows and this is the one request a human composes by hand
 * @param role   {@code viewer} or {@code editor}. Validated in the use case rather than by an enum
 *               binding here: §4.2's values are the contract and the constant names are ours to
 *               rename, which is the same rule {@code SearchRequest.SortOrder} follows
 */
record SkillGrantRequest(String handle, String role) {
}
