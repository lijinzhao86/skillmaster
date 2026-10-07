package com.skillmasterai.modules.version;

/**
 * One share, as the owner's list needs it (ADR 0034).
 *
 * <p>Public because it is what {@link SkillSharingService} hands back, and a module's seam may not
 * be spelled in its internals — the architecture test says so, and it caught this one when the row
 * record was still the internal one.
 *
 * @param granteeId the account it is shared with, still an id. The handle is M1's column, so turning
 *                  this into something a person reads happens one layer up, where M1 can be asked
 *                  — for the whole page in one call
 * @param role      {@code viewer} or {@code editor}, as the column spells it
 * @param createdAt RFC3339 UTC, when access was first given. **Not** when the role last changed:
 *                  editing a role keeps this moment, so it answers "how long has this person had
 *                  access" rather than "when was it last touched"
 */
public record SkillGrant(String granteeId, String role, String createdAt) {
}
