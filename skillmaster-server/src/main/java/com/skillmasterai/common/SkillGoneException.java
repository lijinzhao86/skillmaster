package com.skillmasterai.common;

/**
 * A public skill that had a live version was deleted by its owner (v61, ADR 0184).
 *
 * <p>410 rather than 404: the address is not a typo and the skill is not hidden — the owner said,
 * in public, that it is gone. A private deletion, or a public skill that never had a live version,
 * stays 404, so this answer only exists where the skill was already public and callable.
 */
public class SkillGoneException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SkillGoneException(String message) {
        super(message);
    }
}
