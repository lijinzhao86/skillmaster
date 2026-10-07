package com.skillmasterai.usecase;

import com.skillmasterai.modules.auth.AuthenticatedSubject;
import com.skillmasterai.modules.distribution.DiffService;
import com.skillmasterai.modules.distribution.SkillDiff;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.version.SkillSnapshot;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.modules.version.Caller;
import com.skillmasterai.modules.version.VersionPin;
import com.skillmasterai.modules.version.VersionSummary;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What changed between two versions of the caller's own skill.
 *
 * <p>The policy in here is small and entirely about <strong>defaults</strong>, which is why it is a
 * use case rather than a method on M9: which two versions a comparison means when the caller named
 * only one, or neither. M9 compares two versions it is handed and has no opinion about that.
 *
 * <p><strong>{@code from} defaults to what is live.</strong> That is the question a person opening a
 * draft actually has — "what would publishing this change?" — and it is the same default a review page
 * uses. A skill nothing has been published from has no live version, and that is not a miss to report:
 * every file comes back as an addition, which is the true answer to the same question. The caller can
 * also name any other version, which is how "compare these two" works before v1 grows a version picker
 * (ADR 0031's 后果).
 *
 * <p><strong>Everything is read through the author's scope</strong>, so a draft may be either side.
 * Comparing a draft against live is the whole feature, and comparing two drafts is the same act. A
 * discarded version is readable too: the row and its bytes are still there, and a person asking what
 * they threw away is asking a fair question. None of it is reachable through the consumption plane —
 * that plane has no diff at all.
 *
 * <p>Reading drafts means {@link SkillVersionService#authorSnapshot} rather than the live one, and the
 * difference matters for which failures are which: a version that exists but is not published is an
 * ordinary answer here, where on the consumption plane it would be §4.1's 404.
 */
@Component
public class DiffAuthoredSkillUseCase {

    private final NamespaceService namespaces;
    private final SkillVersionService versions;
    private final DiffService diffs;

    public DiffAuthoredSkillUseCase(NamespaceService namespaces, SkillVersionService versions,
            DiffService diffs) {
        this.namespaces = namespaces;
        this.versions = versions;
        this.diffs = diffs;
    }

    /**
     * @param target which version to look at. {@link VersionPin.Latest} means the live version, or
     *               the most recent submission when nothing is live — the same fallback every other
     *               read on this plane makes, so that a skill made only of drafts can still be opened
     * @param from   the version to compare against. {@link VersionPin.Latest} means the live version
     *               <em>only</em>, and differs from {@code target} in what happens without one: a
     *               skill nothing has been published from has no baseline, so the comparison is
     *               against nothing and every file is an addition. Falling back to the newest
     *               submission here would compare a draft with itself and report no changes at all —
     *               the opposite of the answer the caller asked for
     * @return the comparison, or empty when the skill or either named version is not there — §4.1's
     *         one 404, which cannot distinguish "no such skill" from "no such version of it"
     */
    @Transactional(readOnly = true)
    public Optional<SkillDiff> diff(String namespaceSlug, String name, VersionPin target,
            VersionPin from, AuthenticatedSubject subject) {
        Optional<Namespace> namespace = namespaces.bySlug(namespaceSlug);
        if (namespace.isEmpty()) {
            return Optional.empty();
        }
        String namespaceId = namespace.get().id();
        Caller caller = Callers.of(namespaces, subject);

        // Resolves existence as well as the versions: a skill that is absent or soft-deleted has no
        // versions at all, and one query answers both that and which version is current. It also
        // makes the baseline a row out of the same list the caller is being shown, so a comparison
        // cannot be against a version the page describes as something else.
        List<VersionSummary> known = versions.versionsOf(namespaceId, name);
        if (known.isEmpty()) {
            return Optional.empty();
        }

        Optional<SkillSnapshot> base;
        if (from instanceof VersionPin.Latest) {
            base = liveVersionOf(namespaceId, name, known, caller);
        } else {
            // Named explicitly, so it must exist: answering "everything is new" for a version the
            // caller invented would be a comparison against something that is not there, presented
            // as one that is.
            base = versions.authorSnapshot(namespaceId, name, from, caller);
            if (base.isEmpty()) {
                return Optional.empty();
            }
        }

        return versions.authorSnapshot(namespaceId, name, target, caller)
                .map(targetSnapshot -> diffs.compare(base, targetSnapshot));
    }

    /**
     * The version the pointer names, or empty when nothing is published — resolved through the
     * version list rather than asked for as {@code latest}, because {@code latest} on this plane
     * falls back to the newest submission (see the method contract above).
     */
    private Optional<SkillSnapshot> liveVersionOf(String namespaceId, String name,
            List<VersionSummary> known, Caller caller) {
        return known.stream()
                .filter(VersionSummary::isCurrent)
                .findFirst()
                .flatMap(current -> versions.authorSnapshot(namespaceId, name,
                        new VersionPin.Digest(current.digest()), caller));
    }
}
