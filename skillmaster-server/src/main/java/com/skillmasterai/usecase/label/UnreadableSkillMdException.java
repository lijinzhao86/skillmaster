package com.skillmasterai.usecase.label;

/**
 * The current version has no readable {@code SKILL.md}, so there is nothing to classify.
 *
 * <p>A published skill always has one; this is the defensive answer for a snapshot that predates
 * that rule or a manifest whose root file is binary. It maps to 409 so the operations service
 * records the skill as failed instead of retrying a request that cannot succeed.
 */
public final class UnreadableSkillMdException extends RuntimeException {

    public UnreadableSkillMdException(String name, String reason) {
        super("SKILL.md of '" + name + "' is not readable: " + reason);
    }
}
