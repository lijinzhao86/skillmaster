package com.skillmasterai.usecase.model;

import com.skillmasterai.modules.version.SkillSnapshot;
import com.skillmasterai.modules.version.VersionSummary;
import java.util.List;

/**
 * An author's own view of one skill: every version it has, and one of them in full.
 *
 * <p>Both halves come from M7 and are only meaningful together. The list is what the person chooses
 * between, so it is deliberately unfiltered — a superseded published version is still addressable and
 * a discarded one is a fact about the skill rather than something to hide; {@code selected} is the
 * one currently open, resolved from the same pin the address carried, drafts included.
 *
 * <p>There is no equivalent on the consumption plane, and the difference is the whole of ADR 0031:
 * {@link SubmittedSkill} and {@link com.skillmasterai.modules.distribution.SkillDetail} describe one
 * version, and the reader has no say in which.
 *
 * @param namespaceSlug the namespace the address named, which is the caller's own — the use case
 *                      resolves it before anything else, so this is never somebody else's
 * @param selected      the version the address pinned, or the most recent submission when nothing
 *                      has been published yet. An explicit {@code @N} is honoured whatever the
 *                      version's state, so this **can** be a discarded one — the picker offers them,
 *                      and opening one is how a person checks what they threw away
 */
public record AuthoredSkill(String namespaceSlug, SkillSnapshot selected,
        List<VersionSummary> versions) {

    public AuthoredSkill {
        versions = List.copyOf(versions);
    }
}
