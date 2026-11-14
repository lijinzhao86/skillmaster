package com.skillmasterai.ops.labels;

/**
 * One value the rules found, the rule that found it, and the evidence it found.
 *
 * @param definitionId the stable vocabulary id, e.g. {@code runtime:python}
 * @param ruleId       the rule, e.g. {@code python.command} — what to point at when it is wrong
 */
public record LabelClaim(String definitionId, String ruleId, Evidence evidence) {
}
