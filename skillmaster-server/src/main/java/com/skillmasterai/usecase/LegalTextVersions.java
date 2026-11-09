package com.skillmasterai.usecase;

/**
 * The versions of the two legal texts this deployment is serving (v56).
 *
 * <p>A typed value rather than reading configuration in the use case: no layer below
 * {@code config} depends on {@link com.skillmasterai.config.SkillmasterProperties}, the same detour
 * {@code WelcomeSkills} takes. Registration stores whatever this says, never a version the client
 * supplies.
 *
 * @param terms   the terms of service version, e.g. {@code 1.0}
 * @param privacy the privacy policy version, e.g. {@code 1.0}
 */
public record LegalTextVersions(String terms, String privacy) {
}
