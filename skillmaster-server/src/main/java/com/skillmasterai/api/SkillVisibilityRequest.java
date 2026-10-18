package com.skillmasterai.api;

/**
 * The body of a visibility change (ADR 0048).
 *
 * <p>Two fields because the state is two facts: private, or public at a rung. The pair is
 * validated in the use case — a rung with a private skill, or a missing one with a public skill,
 * is a 400 naming the field rather than a constraint violation surfacing as a 500.
 *
 * @param visibility {@code private} or {@code public}
 * @param publicRung {@code invoke} or {@code browse} when public; absent when private
 */
record SkillVisibilityRequest(String visibility, String publicRung) {
}
