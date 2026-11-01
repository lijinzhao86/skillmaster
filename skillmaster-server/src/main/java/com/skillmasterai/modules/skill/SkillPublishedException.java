package com.skillmasterai.modules.skill;

/**
 * A credential tried to delete a skill that has a version which went live (v37, ADR 0127).
 *
 * <p>Refused rather than deleted because deleting it would take something away from callers who can
 * read it — the rule ADR 0046 has held since v4. The product still lets the owner delete such a
 * skill, but on the web, where a person confirms it; this exception exists for the credential path,
 * and it names the way out instead of pretending the skill is missing.
 */
public class SkillPublishedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String skillName;

    public SkillPublishedException(String skillName) {
        super("the skill '" + skillName + "' has a version that went live; a credential cannot "
                + "delete it — delete it on the web (taking it offline does not clear this)");
        this.skillName = skillName;
    }

    public String skillName() {
        return skillName;
    }
}
