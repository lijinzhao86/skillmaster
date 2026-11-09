package com.skillmasterai.modules.skill;

import com.skillmasterai.modules.skill.internal.SkillCatalogRepository;

/**
 * M12's half of the account purge: the person's rows in other people's namespaces (v56).
 *
 * <p>Separate from {@link SkillCatalogService} because it is a different kind of question — that
 * one lists skills for a reader, this one removes a person's relationships — and because the purge
 * should not have to acquire the module's whole listing seam to erase these tables.
 *
 * <p>What it does <strong>not</strong> touch is the person's own namespace: that is M4's, and its
 * cascade takes the skills, versions, grants and joins rooted there.
 */
public final class SkillPersonalData {

    private final SkillCatalogRepository repository;

    public SkillPersonalData(SkillCatalogRepository repository) {
        this.repository = repository;
    }

    /** Grants received, invitations addressed to them, requests they made, skills they saved. */
    public void deleteFor(String userId) {
        repository.deletePersonalRows(userId);
    }
}
