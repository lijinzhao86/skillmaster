package com.skillmasterai.modules.skill;

/**
 * The tag being removed is not on this skill (v14).
 *
 * <p>404, and a code of its own rather than {@code skill_not_found}: the skill is there and the
 * caller could read it — what is missing is the attachment they named. Saying "no skill at that
 * address" would send them to check an address that is right.
 */
public final class TagNotAttachedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TagNotAttachedException() {
        super("that tag is not on this skill");
    }
}
