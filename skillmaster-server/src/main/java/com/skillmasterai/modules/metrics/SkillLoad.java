package com.skillmasterai.modules.metrics;

import java.time.LocalDate;

/**
 * One successful load of a skill body, reported as a fact.
 *
 * <p>Deliberately carries no visibility or authorship: the caller applies its own policy (for the
 * public signal: public, live, not the author) and reports only the loads it has decided to count.
 * The metrics module never re-derives that decision.
 */
public record SkillLoad(String skillId, String accountId, LocalDate day, UsageScope scope) {
}
