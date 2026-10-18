package com.skillmasterai.api;

/**
 * The body of an access request: the rung the caller wants (ADR 0052).
 *
 * <p>Named for the thing it is rather than the HTTP verb, so that this record and
 * {@link SkillGrantRequest} read as the two different acts they are — asking and giving.
 *
 * @param rung one of the five grantable rungs, and strictly above the caller's own
 */
record SkillAccessRequest(String rung) {
}
