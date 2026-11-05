package com.skillmasterai.usecase;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillLibraryService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.skill.SkillStanding;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Puts a deployment's default skills into a freshly registered account's library (ADR 0144).
 *
 * <p><strong>Best-effort, and deliberately outside the registration transaction.</strong> By the
 * time this runs the account exists and is committed; a failure here must not turn into a failed
 * registration. Each address is attempted independently, and an address the deployment has not
 * published yet is skipped rather than refused — a fresh environment has no official skills, and
 * that is an ordinary state, not a broken one.
 *
 * <p><strong>A join reference, never a copy</strong> (ADR 0082): the row says "in my library", and
 * the official skill's next publish reaches the account without anything here running again.
 */
@Component
public class WelcomeLibraryUseCase {

    private static final Logger log = LoggerFactory.getLogger(WelcomeLibraryUseCase.class);

    private final WelcomeSkills defaults;
    private final NamespaceService namespaces;
    private final SkillMetadataService metadata;
    private final SkillLibraryService library;

    public WelcomeLibraryUseCase(WelcomeSkills defaults, NamespaceService namespaces,
            SkillMetadataService metadata, SkillLibraryService library) {
        this.defaults = defaults;
        this.namespaces = namespaces;
        this.metadata = metadata;
        this.library = library;
    }

    /**
     * Adds every configured address that is joinable right now. Failures are logged; the caller has
     * already finished the action this step follows.
     */
    public void addTo(String userId) {
        String ownNamespaceId;
        try {
            ownNamespaceId = namespaces.personalNamespaceOf(userId).id();
        } catch (RuntimeException e) {
            log.warn("default library: user {} has no personal namespace", userId, e);
            return;
        }
        Caller caller = new Caller(userId, ownNamespaceId);
        for (WelcomeSkills.Skill wanted : defaults.skills()) {
            try {
                addOne(caller, wanted);
            } catch (RuntimeException e) {
                log.warn("default library: could not add {}/{} for {}", wanted.namespaceSlug(),
                        wanted.name(), userId, e);
            }
        }
    }

    private void addOne(Caller caller, WelcomeSkills.Skill wanted) {
        Optional<Namespace> namespace = namespaces.bySlug(wanted.namespaceSlug());
        if (namespace.isEmpty()) {
            log.debug("default library: no namespace '{}'", wanted.namespaceSlug());
            return;
        }
        Optional<SkillStanding> standing =
                metadata.standing(namespace.get().id(), wanted.name(), caller);
        // Not joinable is skipped, not refused: same rule the manual join applies, minus the 409 —
        // a deployment nobody has published the skill into yet is not the registrant's problem.
        if (standing.isEmpty() || !standing.get().discoverable()) {
            log.debug("default library: {}/{} is not joinable right now", wanted.namespaceSlug(),
                    wanted.name());
            return;
        }
        if (standing.get().inLibrary()) {
            return;
        }
        library.join(caller.userId(), standing.get().skillId(), Timestamps.now());
    }
}
