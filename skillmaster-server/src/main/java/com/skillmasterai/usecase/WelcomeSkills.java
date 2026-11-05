package com.skillmasterai.usecase;

import java.util.List;
import java.util.Objects;

/**
 * The skills a new account's library starts with, as configuration declares them (ADR 0144).
 *
 * <p><strong>Addresses, not ids.</strong> Nothing has been resolved yet — the deployment may not
 * have published one of these skills, and that is the ordinary case in a fresh environment. Keeping
 * the unresolved spelling apart from {@code modules.skill.SkillAddress} (which carries ids) is what
 * lets "not there yet" be a skip rather than an error somebody has to catch.
 */
public record WelcomeSkills(List<Skill> skills) {

    public WelcomeSkills {
        skills = List.copyOf(skills);
    }

    public record Skill(String namespaceSlug, String name) {

        public Skill {
            Objects.requireNonNull(namespaceSlug, "namespace slug");
            Objects.requireNonNull(name, "name");
        }
    }
}
