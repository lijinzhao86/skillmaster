package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.distribution.SkillDistributionService;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.modules.version.VersionSummary;
import com.skillmasterai.usecase.model.AuthoredSkill;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading one's own skill: the version list with a selected version, and that version's body.
 *
 * <p>The mirror of {@link ReadSkillUseCase}, and the differences are all one difference — this plane
 * resolves through M7's author scope rather than its live scope. Which version an address names is
 * decided the same way, by the same {@link VersionPin}; what changes is that a draft is an ordinary
 * answer here, and that a skill with nothing published still has something to show rather than being
 * 404.
 *
 * <p>Two answers, one use case, for the reason the consumption plane's three share one: they differ
 * only in how much they return, and the step that matters — resolve the caller's namespace, then let
 * M7 filter on it — is common to them. That step runs even when the answer is going to be nothing.
 *
 * <p>The namespace is still resolved rather than assumed, even though this plane only ever shows a
 * person their own work. An address carries one, and the rule for a namespace that is not the
 * caller's is the same here as there: nothing, never a "forbidden", so that the two are
 * indistinguishable.
 */
@Component
public class ReadAuthoredSkillUseCase {

    private final NamespaceService namespaces;
    private final SkillVersionService versions;
    private final SkillDistributionService distribution;

    public ReadAuthoredSkillUseCase(NamespaceService namespaces, SkillVersionService versions,
            SkillDistributionService distribution) {
        this.namespaces = namespaces;
        this.versions = versions;
        this.distribution = distribution;
    }

    /**
     * The skill with every version it has, one of them in full.
     *
     * <p>The two reads are ordered deliberately: the snapshot resolves existence and the pin, so a
     * skill that is absent, soft-deleted or not the caller's stops here, and the version list is only
     * asked for once there is one.
     *
     * <p><strong>They are two snapshots, not one.</strong> One transaction at the default isolation
     * (READ COMMITTED) does not make two statements agree, and the version list filters on
     * {@code deleted_at IS NULL} — so a soft delete committing between them leaves a resolved
     * snapshot beside an empty list. That combination is not "no such skill" to the repository, but
     * it is exactly that here: the skill was gone by the time the second read ran. So it answers
     * {@link Optional#empty()}, which is what the next request will get, rather than letting the
     * response builder turn an empty list into a 500 about a skill that simply no longer exists.
     * (REPEATABLE READ would answer with a skill that has just been deleted instead; a consistent
     * answer about a row that is gone is not the one to give.)
     */
    @Transactional(readOnly = true)
    public Optional<AuthoredSkill> detail(String namespaceSlug, String name, VersionPin pin,
            AuthenticatedSubject subject) {
        Optional<Namespace> namespace = namespaces.bySlug(namespaceSlug);
        if (namespace.isEmpty()) {
            return Optional.empty();
        }
        return versions.authorSnapshot(namespace.get().id(), name, pin,
                        Callers.of(namespaces, subject))
                .flatMap(selected -> {
                    List<VersionSummary> all = versions.versionsOf(namespace.get().id(), name);
                    return all.isEmpty()
                            ? Optional.empty()
                            : Optional.of(new AuthoredSkill(namespace.get().slug(), selected, all));
                });
    }

    /** The original {@code SKILL.md} bytes, from a version that need not be published. */
    @Transactional(readOnly = true)
    public Optional<byte[]> body(String namespaceSlug, String name, VersionPin pin,
            AuthenticatedSubject subject) {
        return namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> distribution.authorBodyOf(namespace,
                        Callers.of(namespaces, subject), name, pin));
    }

    /**
     * One file of the version, by exact {@code relpath} — L3 for the author's own plane.
     *
     * <p>What the browser's file list opens. Two nothings, kept apart the way §4.1 keeps them: an
     * address that resolves to nothing, versus a version that resolved and does not list this
     * relpath — see {@link SkillDistributionService.FileLookup}.
     */
    @Transactional(readOnly = true)
    public Optional<SkillDistributionService.FileLookup> file(String namespaceSlug, String name,
            VersionPin pin, String relpath, AuthenticatedSubject subject) {
        return namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> distribution.authorFileOf(namespace,
                        Callers.of(namespaces, subject), name, pin, relpath));
    }
}
