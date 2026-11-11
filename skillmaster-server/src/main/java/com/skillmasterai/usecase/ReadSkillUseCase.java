package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.distribution.SkillDetail;
import com.skillmasterai.modules.distribution.SkillDistributionService;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.Caller;
import com.skillmasterai.modules.skill.SkillMetadataService;
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
    private final SkillMetadataService metadata;

    public ReadSkillUseCase(NamespaceService namespaces, SkillDistributionService distribution,
            SkillVersionService versions, SkillMetadataService metadata) {
        this.namespaces = namespaces;
        this.distribution = distribution;
        this.versions = versions;
        this.metadata = metadata;
    }

    /**
     * A resolved stream plus, when the public policy matched, the fact worth counting.
     *
     * @param candidate  null unless this read is a public, non-author body load (M15's policy)
     * @param consumption null unless the file was found; the facts M17 records
     */
    public record StreamedFile(SkillDistributionService.StreamLookup lookup,
            SkillUseCandidate candidate, SkillConsumption consumption) {
    }

    /** The two facts the recorder needs; the policy has already been applied here. */
    public record SkillUseCandidate(String skillId, String accountId) {
    }

    /**
     * The one guard every consumption read shares (v6).
     *
     * <p>A skill switched off by its owner answers 503 for every read, pinned ones included. It is
     * applied after the access lookup, so a caller who may not see the skill still gets 404 and the
     * switch discloses nothing. The author plane does not come through here: an offline skill is
     * still the author's to manage.
     */
    private static Access serving(Access access) {
        // The due date is the state, not the job: a ramp that reached its date is offline even if
        // the daily materialiser has not run yet (v61, ADR 0184).
        if (access.offlineNow()) {
            throw new com.skillmasterai.common.SkillOfflineException("'" + access.name()
                    + "' is offline; its owner has stopped serving it. Try again later."
                    + " Reading or managing it from the console still works.");
        }
        return access;
    }

    /**
     * The access lookup every consumption read starts with, plus v61's one extra answer: a public
     * skill that had a live version and was deleted returns 410 instead of the 404 an empty lookup
     * gives (ADR 0184). Private deletions and never-published ones stay 404.
     */
    private Optional<Access> accessible(com.skillmasterai.modules.namespace.Namespace namespace,
            String name, Caller caller) {
        Optional<Access> access = metadata.accessFor(namespace.id(), name, caller);
        if (access.isEmpty() && metadata.deletedPublicEverPublished(namespace.id(), name)) {
            throw new com.skillmasterai.common.SkillGoneException(
                    "'" + name + "' was deleted by its owner; its address is no longer served");
        }
        return access;
    }

    /** L1: the full manifest and no content. Needs {@code browse} (ADR 0047). */
    @Transactional(readOnly = true)
    public Optional<SkillDetail> detail(String namespaceSlug, String name, VersionPin pin,
            AuthenticatedSubject subject) {
        Caller caller = Callers.of(namespaces, subject);
        return namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> accessible(namespace, name, caller)
                        .map(ReadSkillUseCase::serving)
                        .flatMap(access -> distribution.detailOf(namespace, access, pin)));
    }

    /**
     * L1 alone: the metadata, without the manifest. Needs {@code invoke} only.
     *
     * <p>The same skill and the same address grammar as {@link #detail}; what changes is that the
     * file list is not part of the answer, which is the whole of the two rungs' difference on this
     * plane (ADR 0047 §决定 1). Routing it through the detail read would have made `/metadata`
     * require {@code browse} — the one endpoint whose purpose is to be reachable by the lowest rung.
     */
    @Transactional(readOnly = true)
    public Optional<SkillDetail> metadata(String namespaceSlug, String name, VersionPin pin,
            AuthenticatedSubject subject) {
        Caller caller = Callers.of(namespaces, subject);
        return namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> accessible(namespace, name, caller)
                        .map(ReadSkillUseCase::serving)
                        .flatMap(access -> distribution.metaOf(namespace, access, pin)));
    }

    /** L2: the original {@code SKILL.md} bytes. */
    @Transactional(readOnly = true)
    public Optional<byte[]> body(String namespaceSlug, String name, VersionPin pin,
            AuthenticatedSubject subject) {
        Caller caller = Callers.of(namespaces, subject);
        return namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> accessible(namespace, name, caller)
                        .map(ReadSkillUseCase::serving)
                        .flatMap(access -> distribution.bodyOf(namespace, access, pin)));
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
        Caller caller = Callers.of(namespaces, subject);
        return namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> accessible(namespace, name, caller)
                        .map(ReadSkillUseCase::serving)
                        .flatMap(access -> distribution.fileOf(namespace, access, pin, relpath)));
    }

    /**
     * L3 for a response that streams (v7): the same file, resolved the same way, with the bytes
     * fetched in chunks as the client reads.
     *
     * <p>No bytes cross this method — it returns how to get them. The transaction that resolved the
     * version closes before the first chunk is fetched, which is correct rather than a gap: the
     * blob is immutable once written, so the later statement reads the same content the resolution
     * promised.
     */
    @Transactional(readOnly = true)
    public Optional<StreamedFile> streamFile(String namespaceSlug,
            String name, VersionPin pin, String relpath, AuthenticatedSubject subject) {
        Caller caller = Callers.of(namespaces, subject);
        return namespaces.bySlug(namespaceSlug)
                .flatMap(namespace -> accessible(namespace, name, caller)
                        .map(ReadSkillUseCase::serving)
                        .flatMap(access -> distribution.streamFileOf(namespace, access, pin, relpath)
                                .map(resolved -> {
                                    // **Loading the body is the use**, not opening the metadata
                                    // page. Both the `/body` route and `/files/SKILL.md` land here,
                                    // and a failed lookup (no such file) must not count. The policy
                                    // stays here; recording waits until this transaction is over
                                    // (ADR 0153) so it cannot hold a second connection.
                                    boolean found = resolved.lookup()
                                            instanceof SkillDistributionService.StreamLookup.Found;
                                    boolean counts = found
                                            && SkillDistributionService.BODY_RELPATH.equals(relpath)
                                            && "public".equals(access.visibility())
                                            && !access.owns();
                                    return new StreamedFile(resolved.lookup(), counts
                                            ? new SkillUseCandidate(access.skillId(),
                                                    subject.userId())
                                            : null, found
                                            ? new SkillConsumption(access.skillId(),
                                                    namespace.ownerUserId(),
                                                    resolved.snapshot().id(),
                                                    resolved.snapshot().version(),
                                                    namespace.slug() + "/" + access.name())
                                            : null);
                                })));
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
                .flatMap(namespace -> accessible(namespace, name, caller)
                        .map(ReadSkillUseCase::serving)
                        .flatMap(versions::liveVersionsOf));
    }
}
