package com.skillmasterai.modules.version;

import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.GrantRole;
import com.skillmasterai.modules.skill.NotPermittedException;
import com.skillmasterai.modules.version.internal.VersionRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Internal collaborator extracted from {@link SkillVersionService}. */
final class VersionReader {

    private final VersionRepository versions;
    private final VersionRows rows;

    VersionReader(VersionRepository versions, VersionRows rows) {
        this.versions = versions;
        this.rows = rows;
    }

    /**
     * One version for an author-plane scan — {@code grep} (ADR 0098).
     *
     * <p><strong>The same rule {@code files}, {@code wc} and {@code cat} use</strong>: a
     * {@code browse} caller needs, and the pin resolves against the caller's visible states. A
     * draft is therefore searchable only for {@code write} and up, a revoked or discarded row only
     * for {@code maintain} and up, and a published one for everyone who may read the skill. There is
     * deliberately no draft-only sibling any more: two rules for one question is how "I can
     * {@code cat} it but not grep it" happens.
     */
    public Optional<VersionSummary> openForSearch(Access access, VersionPin pin) {
        if (!access.atLeast(GrantRole.BROWSE)) {
            throw new NotPermittedException("searching '" + access.name() + "' files needs the "
                    + "`browse` rung");
        }
        return versionOf(access, pin).map(row -> rows.summary(access, row));
    }

    /**
     * One row by pin, whatever its state — the answer a write echoes back.
     *
     * <p>A write's response is about the row it just changed, so it must not go through the
     * read-visibility filter: a {@code write} submitter who discards their own draft would otherwise
     * get 404 for the row they just discarded (and a second discard would be indistinguishable from
     * a failure). Permission is decided by the caller before this is used.
     */
    public Optional<VersionSummary> authorVersionIgnoringState(Access access, VersionPin pin) {
        return versions.findByPinIgnoringState(access.skillId(), pin)
                .map(row -> new VersionSummary(row.id(), row.version(), row.draftName(),
                        row.note(), row.digest(), row.title(), row.fileCount(), row.totalBytes(),
                        row.submittedAt(), row.submittedBy(), row.state(), row.stateAt(),
                        row.firstPublishedAt(), row.revokedReason(),
                        row.id().equals(access.currentVersionId()), row.releaseType()));
    }

    /**
     * The versions of a skill this rung may read, in submission order — the author list.
     *
     * <p>Returned whole; the caller (the use case) applies the status filter, the search and the
     * page, because the search matches a submitter's handle and that is M1's word, not M7's.
     */
    public List<VersionSummary> authorVersions(Access access) {
        return versions.versionsVisible(access.skillId(), access.currentVersionId(),
                rows.visibleStates(access));
    }

    /**
     * One of a skill's published versions, for the consumption plane, or empty when there is
     * nothing that plane may read.
     *
     * <p><strong>Three conditions, all checked.</strong> The skill must not be soft-deleted, the
     * version must exist, <em>and</em> it must be published. Resolving the version row alone would
     * keep serving a soft-deleted skill, because a soft delete leaves {@code skill_version} and
     * {@code version_file} in place (§3.3 point 5) — the mistake ADR 0012's 后果 section names.
     * Dropping the third condition would serve a draft, which is ADR 0031's version of the same
     * mistake.
     */
    public Optional<SkillSnapshot> liveSnapshot(Access access, VersionPin pin) {
        return liveSnapshotAtLeast(access, pin, GrantRole.INVOKE);
    }

    /**
     * The consumption plane's detail: L1 <em>and the file manifest</em>, which is {@code browse}
     * (ADR 0047's ladder, applied to the consumption plane too).
     *
     * <p><strong>Body and single-file reads stay at {@code invoke}; this one does not.</strong> The
     * whole of the two rungs' difference is whether a caller can enumerate the files — "take the one
     * you can name, but discover nothing" — and the manifest is the enumeration. A caller at
     * {@code invoke} who asks anyway is a 403, not a 404: the skill is in their listing, and the
     * refusal is about this rung rather than existence.
     */
    public Optional<SkillSnapshot> detailedSnapshot(Access access, VersionPin pin) {
        return liveSnapshotAtLeast(access, pin, GrantRole.BROWSE);
    }

    private Optional<SkillSnapshot> liveSnapshotAtLeast(Access access, VersionPin pin,
            String required) {
        if (!access.atLeast(required)) {
            throw new NotPermittedException("reading the file list of '" + access.name()
                    + "' needs the `" + required + "` rung");
        }
        return resolveForConsumption(access, pin);
    }

    /**
     * One of a skill's versions for the author plane, at the {@code browse} rung or above.
     *
     * <p><strong>The author plane is per rung since ADR 0051, and the rung is the version's.</strong>
     * Detail, body, a single file and a comparison are {@code browse} for a published version, and
     * the state set carries the rest: a draft resolves only for {@code write} and up, a revoked or
     * discarded row only for {@code maintain} and up. That is why there is one entry point rather
     * than a draft-specific sibling — the pin is resolved against the caller's visible states, so a
     * caller cannot reach a draft through a boolean.
     *
     * <p><strong>Visible but below the rung is a 403, not a 404.</strong> The caller can read this
     * skill — it is in their listing or it was handed to them by the public rung — so pretending it
     * does not exist would be false, and {@link NotPermittedException} is how the service says
     * "you may see it, this rung is not enough" (the same answer the write paths give).
     */
    public Optional<SkillSnapshot> authorSnapshot(Access access, VersionPin pin) {
        return authorSnapshotAtLeast(access, pin, GrantRole.BROWSE);
    }

    private Optional<SkillSnapshot> authorSnapshotAtLeast(Access access, VersionPin pin,
            String required) {
        if (!access.atLeast(required)) {
            throw new NotPermittedException("reading '" + access.name() + "' at this level needs "
                    + "the `" + required + "` rung");
        }
        // The state set carries the rule: below `write` a draft resolves to nothing, and below
        // `maintain` a revoked or discarded row resolves to nothing — exactly as on the list.
        return versionOf(access, pin).map(version -> toSnapshot(access, version));
    }

    /**
     * One skill as the author plane's page needs it, filtered to the caller's rung (ADR 0051).
     *
     * <p>This is the detail route's answer, and it is deliberately wider than
     * {@link #authorSnapshot} in one direction and narrower in another. Wider: a caller at
     * {@code invoke} gets a page at all — the skill's L1 and its published versions — rather than
     * a 403 followed by stitching the same facts together from the API plane. Narrower: everyone
     * below {@code write} gets published versions only, and below {@code browse} the file manifest
     * is not part of the answer ({@code filesVisible} is false).
     *
     * <p><strong>Nothing published is not an empty result.</strong> ADR 0051 §后果 keeps that
     * apart from the consumption plane's 404: a {@code browse}-level caller on a skill whose
     * versions are all drafts gets the skill's L1 with no selected version, which is the empty
     * tree the page renders. An explicit pin that names nothing, on the other hand, is the ordinary
     * 404 — the address asked for a version and there is none they may see.
     */
    public Optional<AuthoredSkillView> authoredView(Access access, VersionPin pin) {
        Optional<VersionRepository.VersionRow> chosen = versionOf(access, pin);
        if (chosen.isEmpty() && !(pin instanceof VersionPin.Latest)) {
            // An explicit pin that resolves to nothing is §4.1's one 404. `latest` without a
            // published version is the empty-tree case above, not a miss.
            return Optional.empty();
        }

        SkillSnapshot selected = chosen.map(version -> toSnapshot(access, version)).orElse(null);
        List<VersionSummary> visible = versions.versionsVisible(access.skillId(),
                access.currentVersionId(), rows.visibleStates(access));

        // With nothing selected, the skill row's copy is the only description there is. It is the
        // current published version's projection when one exists, which is exactly the public
        // shape ADR 0051 says this page renders when no version is live.
        return Optional.of(new AuthoredSkillView(
                access.skillId(),
                access.namespaceId(),
                access.name(),
                selected != null ? selected.title() : access.title(),
                selected != null ? selected.description() : access.description(),
                selected != null ? selected.frontmatterJson() : access.frontmatterJson(),
                selected,
                visible,
                access.atLeast(GrantRole.BROWSE),
                access.offlineAt()));
    }

    /**
     * The skill row's own view, with no version selected — the empty base a new version starts
     * from when there is neither a live version nor a draft to copy (v8 TD §读).
     *
     * <p>Deliberately not {@link #authoredView} with {@link VersionPin.Latest}: that fallback can
     * select a discarded row for {@code maintain} and above, which is a version the editor must not
     * take as its base.
     */
    public AuthoredSkillView blankAuthoredView(Access access) {
        List<VersionSummary> visible = versions.versionsVisible(access.skillId(),
                access.currentVersionId(), rows.visibleStates(access));
        return new AuthoredSkillView(access.skillId(), access.namespaceId(), access.name(),
                access.title(), access.description(), access.frontmatterJson(), null, visible,
                access.atLeast(GrantRole.BROWSE), access.offlineAt());
    }

    /**
     * The version a pin selects, or empty when it names one that does not exist.
     *
     * <p>The failure modes are deliberately not the same answer. A pointer naming a version that is
     * not published is our own corruption — {@link #publishVersion} marks the version published in
     * the same transaction as the move — and it is loud, because answering 404 would blame the
     * caller for it. A pinned version that is absent, on the other hand, is an ordinary miss. And a
     * skill with no pointer at all is neither: since ADR 0031 it is how every skill starts.
     */
    private Optional<VersionRepository.VersionRow> versionOf(Access access, VersionPin pin) {
        if (access.currentVersionId() == null && pin instanceof VersionPin.Latest) {
            // Nothing has been published from this skill, and the caller asked for whatever is
            // current. The consumption plane has nothing to serve; the author's view falls back to
            // the most recent submission, so a skill made only of drafts still has something to
            // look at.
            //
            // The `Latest` in the condition is load-bearing, and its absence was a bug: a *pinned*
            // address names one version and has to resolve to that one or to nothing, and letting it
            // fall through to the newest submission answered `@99` with `@1` on a skill that had
            // never been published. That is the property ADR 0012 exists to hold — `@3` is version 3
            // — and it cannot depend on whether anything happens to be live.
            //
            // The second half covers a skill whose every version was discarded: it is still in the
            // author's listing, and this address is what that row links to. Answering 404 there made
            // the page deny a skill the list was showing.
            Set<String> visible = rows.visibleStates(access);
            // **The fallback skips revoked for every rung** (v6, TD §版本状态机): the author has
            // just taken that version down, and a screen that opens onto it would answer a question
            // nobody asked. It stays reachable by pin for `maintain` and above.
            return versions.findNewestNotDiscarded(access.skillId(), false)
                    .filter(version -> visible.contains(version.state()))
                    .or(() -> versions.findNewest(access.skillId())
                            .filter(version -> visible.contains(version.state()))
                            .filter(version -> !VersionState.REVOKED.equals(version.state())));
        }
        if (pin instanceof VersionPin.Latest) {
            return Optional.of(versions.findCurrent(access.currentVersionId())
                    .orElseThrow(() -> new IllegalStateException("skill " + access.skillId()
                            + " points at version " + access.currentVersionId()
                            + ", which is not a published version")));
        }
        return versions.findVisible(access.skillId(), pin, rows.visibleStates(access));
    }

    /**
     * The consumption plane's resolution of one pin: published serves, revoked is 410, everything
     * else is 404 (ADR 0058).
     *
     * <p>The three-way answer is why this cannot be the ordinary lookup: those filter to published,
     * which erases "there was one and it was taken down" into "no such version". Only a caller
     * already holding the pin reaches here, so naming the state discloses nothing new.
     *
     * <p>The {@code invoke} floor is checked here; the {@code browse} floor that the manifest needs
     * stays in {@link #liveSnapshotAtLeast}, because the two callers require different rungs and one
     * {@code Access} cannot say which.
     */
    public Optional<SkillSnapshot> resolveForConsumption(Access access, VersionPin pin) {
        if (!access.atLeast(GrantRole.INVOKE)) {
            throw new NotPermittedException("reading '" + access.name()
                    + "' needs the `invoke` rung");
        }
        if (pin instanceof VersionPin.Latest) {
            if (access.currentVersionId() == null) {
                return Optional.empty();
            }
            return Optional.of(versions.findCurrent(access.currentVersionId())
                    .orElseThrow(() -> new IllegalStateException("skill " + access.skillId()
                            + " points at version " + access.currentVersionId()
                            + ", which is not a published version")))
                    .map(version -> toSnapshot(access, version));
        }
        Optional<VersionRepository.VersionRow> found =
                versions.findByPinIgnoringState(access.skillId(), pin);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        VersionRepository.VersionRow version = found.get();
        if (VersionState.REVOKED.equals(version.state())) {
            throw new VersionRevokedException("'" + rows.describe(pin) + "' of '" + access.name()
                    + "' was taken down by its author and is no longer readable");
        }
        if (!VersionState.PUBLISHED.equals(version.state())) {
            return Optional.empty();
        }
        return Optional.of(toSnapshot(access, version));
    }

    /**
     * The version's own metadata, not the skill row's.
     *
     * <p>They agree while the pin is the current version and diverge the moment it is not, and the
     * divergent case is the one that has to be right: a caller that asked for {@code @1} is looking
     * at {@code @1}, and describing it with {@code @2}'s title and description would be the same
     * class of mistake as serving {@code @1}'s manifest with {@code @2}'s bytes. The skill row's
     * copy exists for search, which reads it on the hot path.
     */
    /**
     * One version built without a caller, for the service-to-service plane (v72).
     *
     * <p><strong>The plane is the gate.</strong> This method does not ask who may read the version;
     * its only caller is the inner label endpoint, which has already restricted itself to public
     * official skills through the discovery predicate. Exposing it to a user-facing path would be
     * a second answer to "who may read this", which is the one thing M7 refuses to own.
     */
    public Optional<SkillSnapshot> innerSnapshot(String skillId, String namespaceId, String name,
            String versionId, String currentVersionId) {
        return versions.findByRowId(skillId, versionId)
                .map(version -> new SkillSnapshot(skillId, version.id(), namespaceId, name,
                        version.title(), version.description(), version.frontmatter(),
                        version.version(), version.digest(), version.fileCount(),
                        version.totalBytes(), version.state(), version.stateAt(),
                        version.firstPublishedAt(), version.id().equals(currentVersionId),
                        versions.filesOf(version.id())));
    }

    private SkillSnapshot toSnapshot(Access access, VersionRepository.VersionRow version) {
        return new SkillSnapshot(
                access.skillId(), version.id(), access.namespaceId(), access.name(), version.title(),
                version.description(), version.frontmatter(), version.version(), version.digest(),
                version.fileCount(), version.totalBytes(), version.state(), version.stateAt(),
                version.firstPublishedAt(), version.id().equals(access.currentVersionId()),
                versions.filesOf(version.id()));
    }

    /**
     * Every version of a skill, newest first, for the author's own list.
     *
     * <p>Deliberately unfiltered: it is the list a person chooses from, so a discarded version is
     * shown as discarded rather than made to look like it never existed, and a superseded published
     * version still appears because it is still addressable — somebody may hold {@code @3}.
     */
    public List<VersionSummary> versionsOf(Access access) {
        return versions.versionsOf(access.skillId(), access.currentVersionId());
    }

    /**
     * The versions a consumer may invoke, or empty when this caller has nothing to invoke.
     *
     * <p><strong>An empty list is not the answer for "nothing published".</strong> A skill made only
     * of drafts answers 404 everywhere else on this plane, so an empty list here would be this one
     * entry point saying "it exists, there is nothing in it" where the rest of the plane says
     * "there is no such thing" — one address, two meanings.
     */
    public Optional<List<VersionSummary>> liveVersionsOf(Access access) {
        List<VersionSummary> published =
                versions.publishedVersionsOf(access.skillId(), access.currentVersionId());
        return published.isEmpty() ? Optional.empty() : Optional.of(published);
    }
}
