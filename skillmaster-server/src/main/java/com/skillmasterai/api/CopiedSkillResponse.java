package com.skillmasterai.api;

import com.skillmasterai.usecase.model.CopiedSkill;

/**
 * What a copy answers with (v54): the new address, its first draft, and the source as it read.
 *
 * <p>201 on both planes, because a new skill came into being. The rest of the body is shaped like
 * {@code skill create}'s response plus the two source facts — a client that already knows how to
 * read a created skill needs nothing new.
 *
 * @param draft the new draft's short code, which is the pin the client acts on next
 * @param version null because a draft has no version number yet (ADR 0055)
 */
record CopiedSkillResponse(Skill skill, String draft, String draftName, String version,
        Source source) {

    record Skill(String namespace, String name) {
    }

    record Source(String address, String version) {
    }

    static CopiedSkillResponse of(CopiedSkill copied) {
        return new CopiedSkillResponse(
                new Skill(copied.namespaceSlug(), copied.name()),
                copied.draftId(),
                copied.draftName(),
                copied.version(),
                new Source(copied.sourceAddress(), copied.sourceVersion()));
    }
}
