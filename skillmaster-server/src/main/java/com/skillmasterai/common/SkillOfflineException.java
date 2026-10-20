package com.skillmasterai.common;

/**
 * The whole skill is switched off by its owner.
 *
 * <p>503 rather than 404: the skill exists and the caller may see it, it is just not serving right
 * now. That is a different answer from every other unreadable address on the consumption plane, and
 * it is the one that tells a caller to come back rather than that they were wrong. A caller who may
 * <em>not</em> see the skill still gets 404 — switching off must not disclose what was hidden.
 *
 * <p>Lives in {@code common} because the check sits in the use case, above both M12 (which owns
 * {@code offline_at}) and the API layer that maps it.
 */
public class SkillOfflineException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SkillOfflineException(String message) {
        super(message);
    }
}
