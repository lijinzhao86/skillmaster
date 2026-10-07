package com.skillmasterai.modules.version;

/**
 * A submission declared a version name this skill already has, with different content.
 *
 * <p>Refused rather than absorbed. A version name is what makes {@code @1.2.3} mean one piece of
 * content for ever, so a second, different one under the same name would make the address ambiguous
 * — and silently answering with a new name (a bumped patch) would send back a version the author
 * never wrote, which their own SKILL.md would then disagree with (ADR 0033).
 *
 * <p>Distinct from a replay: identical content under a taken name is the digest that already exists,
 * and ADR 0005's idempotence answers 200 with that version rather than this. The caller can only
 * tell the two apart by looking, which is why {@link SkillVersionService#submit} looks.
 *
 * <p>It is 400 {@code version_already_exists}, not a 404: the caller is the author, the version is
 * right there, and the message has to say what to do about it — a submit that failed silently in
 * this way reads exactly like one that worked.
 */
public class VersionNameTakenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public VersionNameTakenException(String message) {
        super(message);
    }
}
