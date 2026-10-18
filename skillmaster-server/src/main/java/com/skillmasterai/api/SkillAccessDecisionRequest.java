package com.skillmasterai.api;

/**
 * The body of an approval: the rung being granted, which may differ from the one asked for
 * (ADR 0052 §决定 3).
 *
 * <p>Denials carry no body: there is nothing to choose.
 *
 * @param rung the rung to grant
 */
record SkillAccessDecisionRequest(String rung) {
}
