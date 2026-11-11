package com.skillmasterai.modules.version;

/**
 * Publishing to a name whose skill was soft-deleted.
 *
 * <p>Refused rather than treated as a resurrection, because §4.3 gives restoring its own endpoint.
 * If a publish silently undeleted, deletion would be a suggestion — and the author who deleted the
 * skill would have no way to tell that a later publish over the same name brought it back.
 */
public class SkillDeletedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String skillName;

    public SkillDeletedException(String skillName) {
        super("the skill '" + skillName + "' was deleted while this version was being published;"
                + " submit the version again, or restore the deleted skill");
        this.skillName = skillName;
    }

    public String skillName() {
        return skillName;
    }
}
