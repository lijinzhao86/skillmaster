package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.distribution.SkillDetail;
import com.skillmasterai.modules.distribution.SkillDistributionService;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.version.Caller;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.modules.version.VersionSummary;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading a skill at L1 (detail), L2 (body) and L3 (one file) — one use case, three answers.
 *
 * <p>They are one use case because they differ only in how much they return; splitting them would
 * duplicate the step that actually matters. That step is the composition §2.5 names "读详情": M3
 * has already established who is asking, M4 resolves which namespace they own, and M9 does the
 * reading with that namespace as a predicate. Answering "which skill may I see" in any one of
 * those modules alone would be wrong — M4 cannot name the {@code skill} table, and M9 must not
 * decide policy — so the meeting point is here.
 *
 * <p><strong>The address's first segment is no longer checked against the caller's own
 * namespace.</strong> It used to be, and that check was the whole of the authorization; since ADR
 * 0034 a shared skill lives in somebody else's namespace, so the namespace is resolved by slug and
 * the caller's standing is decided where the skill is found ({@code SkillVersionService}). What
 * still runs on every call, including the ones that turn out to be 404, is the lookup of the
 * caller's own namespace — it is one half of the predicate, and it is not an optimization to be
 * skipped when the answer "looks like" it will be empty.
 *
 * <p>Read-only, so there is no transaction to draw — {@code @Transactional(readOnly = true)} is
 * declared anyway so that the three-query read sees one consistent snapshot rather than a version
 * that could be swapped underneath it by a concurrent publish.
 */
@Component
public class ReadSkillUseCase {

    private final NamespaceService namespaces;
    private final SkillDistributionService distribution;
    private final SkillVersionService versions;

    public ReadSkillUseCase(NamespaceService namespaces, SkillDistributionService distribution,
            SkillVersionService versions) {
        this.namespaces = namespaces;
        this.distribution = distribution;
        this.versions = versions;
    }

    /** L1: the full manifest and no content. */
    @Transactional(readOnly = true)
    public Optional<SkillDetail> detail(String namespaceSlug, String name, VersionPin pin,
            AuthenticatedSubject subject) {
        return namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> distribution.detailOf(namespace,
                        Callers.of(namespaces, subject), name, pin));
    }

    /** L2: the original {@code SKILL.md} bytes. */
    @Transactional(readOnly = true)
    public Optional<byte[]> body(String namespaceSlug, String name, VersionPin pin,
            AuthenticatedSubject subject) {
        return namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> distribution.bodyOf(namespace,
                        Callers.of(namespaces, subject), name, pin));
    }

    /**
     * L3: one file's original bytes, by exact {@code relpath}.
     *
     * <p>The result keeps §4.1's two 404s apart — nothing at this address, versus a version that
     * resolved and does not list the file. See {@link SkillDistributionService.FileLookup}.
     */
    @Transactional(readOnly = true)
    public Optional<SkillDistributionService.FileLookup> file(String namespaceSlug, String name,
            VersionPin pin, String relpath, AuthenticatedSubject subject) {
        return namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> distribution.fileOf(namespace,
                        Callers.of(namespaces, subject), name, pin, relpath));
    }

    /**
     * Which versions of a skill this caller may invoke, newest submission first.
     *
     * <p><strong>It goes to M7 rather than through M9, and that is a deliberate break with its three
     * neighbours above.</strong> They are content at three sizes and M9 is the thing that turns "the
     * namespace you named" into "a skill you may read"; this is not content at any size — no version
     * is selected, so there is no pin and no manifest. The author's own version list is already read
     * this way ({@code ReadAuthoredSkillUseCase}), and having the two planes ask M7 the same question
     * is worth more than a uniform call graph.
     *
     * <p>Empty is the ordinary 404, and it covers three cases that are deliberately one answer: no
     * such namespace, a skill this caller may not see, and a skill with nothing published yet.
     */
    @Transactional(readOnly = true)
    public Optional<List<VersionSummary>> versions(String namespaceSlug, String name,
            AuthenticatedSubject subject) {
        Caller caller = Callers.of(namespaces, subject);
        return namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> versions.liveVersionsOf(namespace.id(), name, caller));
    }
}
