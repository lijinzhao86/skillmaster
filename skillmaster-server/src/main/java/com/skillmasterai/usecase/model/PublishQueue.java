package com.skillmasterai.usecase.model;

import java.util.List;

/**
 * The publish queue, as the read produces it: skills with the drafts waiting on them, newest first.
 *
 * @param skills one entry per skill that has at least one waiting draft, ordered by their newest
 *               draft (the page renders this order and does not re-sort — TD §接口)
 * @param truncated whether more skills were waiting than the answer could carry. True means the page
 *                  must say so; a silent cut would read as "nothing else has drafts"
 */
public record PublishQueue(List<Skill> skills, boolean truncated) {

    /**
     * @param namespace the slug, which is what a person reads. The id is M12's and stays here
     * @param currentVersion the version consumers get, or null when nothing is published yet
     * @param currentSubmittedAt when that version was submitted, or null
     * @param drafts newest first
     */
    public record Skill(String namespace, String name, String currentVersion,
            String currentSubmittedAt, List<Draft> drafts) {
    }

    /** @param code the pin, bare (ADR 0063) */
    public record Draft(String code, String name, String releaseType, String submittedAt) {
    }
}
