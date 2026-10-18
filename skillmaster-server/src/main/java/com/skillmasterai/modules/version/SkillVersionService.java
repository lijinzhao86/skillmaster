package com.skillmasterai.modules.version;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.DraftProjection;
import com.skillmasterai.modules.skill.GrantRole;
import com.skillmasterai.modules.skill.NotPermittedException;
import com.skillmasterai.modules.skill.SkillMetadata;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.skill.VersionProjection;
import com.skillmasterai.modules.version.internal.BlobGc;
import com.skillmasterai.modules.version.internal.VersionRepository;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * M7: the skill's immutable versions, the bytes they reference, and the draft / publish lifecycle.
 *
 * <p><strong>Submitting and publishing are separate operations here</strong> (ADR 0031). Submitting
 * records a version and stops; nothing the consumption plane can see has changed. Publishing marks
 * a version live and — since ADR 0053 — hands the facts to M12, which owns the skill row and moves
 * the pointer.
 *
 * <p><strong>This class never opens a transaction.</strong> §2.5 rule 2 puts cross-module
 * transaction boundaries in the use-case layer, and both operations are nothing but cross-module —
 * so every method here is written to be called inside a transaction the caller owns. Running one
 * outside a transaction would still work, and would be wrong: the version row, its files and the
 * projection would each commit separately.
 *
 * <p><strong>It does not read {@code skill} or {@code skill_grant}, and it does not decide who may
 * do what from them.</strong> The caller asks M12 for an {@link Access} once and passes it in; this
 * module evaluates the rung it carries. That is ADR 0053's rule 2, and it is what keeps "who may
 * see this" in one statement.
 */
public final class SkillVersionService {

    private final VersionRepository versions;
    private final BlobGc blobGc;
    private final SkillMetadataService metadata;

    public SkillVersionService(VersionRepository versions, BlobGc blobGc,
            SkillMetadataService metadata) {
        this.versions = versions;
        this.blobGc = blobGc;
        this.metadata = metadata;
    }

    /**
     * Runs a change that may add or remove a reference to a blob, under the GC's lock, then sweeps.
     *
     * <p><strong>The order is not negotiable and is why this wrapper exists.</strong>
     * {@link BlobGc#beginExclusiveWrite} must come before anything writes {@code version_file} —
     * the lock's own note explains the deadlock a later request causes. And the skill row's lock
     * (taken by M12's {@code findOrCreate}, inside this section) must come <em>after</em> it rather
     * than before, or a submit would hold the row while waiting for the GC lock that a soft delete
     * already holds, and the two would deadlock. The use case therefore puts both the address
     * creation and the version write inside one call to this method.
     */
    public <T> T underExclusiveWrite(Supplier<T> change) {
        blobGc.beginExclusiveWrite();
        T result = change.get();
        blobGc.sweep();
        return result;
    }

    /**
     * Records a version of a skill M12 has already found or created, as a draft.
     *
     * <p>The blobs are stored by the caller before this is called: M7 deals in {@link Manifest},
     * which carries digests and sizes, and never touches bytes. That keeps the
     * {@link com.skillmasterai.modules.blob.BlobStore} seam the only way to reach content.
     *
     * <p>Must be called inside {@link #underExclusiveWrite}.
     */
    public SubmitOutcome submit(String skillId, SkillMetadata skill, Manifest manifest,
            String submittedBy, String source, String at) {
        return record(skillId, skill, manifest, submittedBy, source, at);
    }

    /**
     * Records a version of a skill that already exists, as a draft — the {@code write} path
     * (ADR 0034).
     *
     * <p><strong>It cannot create a skill, and that is the point of having it at all.</strong>
     * Submitting into somebody else's namespace is not expressible through {@link #submit}: the
     * target there is resolved by the use case from the caller's own namespace and never from the
     * request, which is what keeps a submission from being a cross-namespace write primitive. Here
     * the target <em>is</em> named, so the one thing that makes that safe is that it can only ever
     * add a version to a skill the caller has already been given write access to — never bring one
     * into being. Creatable names stay confined to namespaces the caller owns.
     *
     * <p>Must be called inside {@link #underExclusiveWrite}.
     *
     * @return what was recorded
     * @throws NotPermittedException when the caller may read it and may not write it
     */
    public Optional<SubmitOutcome> submitVersion(Access access, SkillMetadata skill,
            Manifest manifest, String submittedBy, String source, String at) {
        if (!access.atLeast(GrantRole.WRITE)) {
            throw new NotPermittedException("'" + access.name() + "' is shared with you below the "
                    + "`write` rung; submitting versions needs `write`");
        }
        return Optional.of(record(access.skillId(), skill, manifest, submittedBy, source, at));
    }

    /**
     * The half of a submission both entry points share: insert or recognise, write the manifest,
     * read back, and rewrite the draft projection.
     *
     * <p>Extracted when {@link #submitVersion} arrived, and the extraction is the honest one — the
     * two differ in how the skill is found and in nothing else.
     */
    private SubmitOutcome record(String skillId, SkillMetadata skill, Manifest manifest,
            String submittedBy, String source, String at) {
        Optional<String> inserted =
                versions.insertIfAbsent(skillId, skill, manifest.digest(), manifest.fileCount(),
                        manifest.totalBytes(), source, submittedBy, at);

        if (inserted.isPresent()) {
            versions.insertFiles(inserted.get(), manifest);
        } else if (versions.findBySkillAndDigest(skillId, manifest.digest(), false).isEmpty()) {
            // The insert wrote nothing, and the content is not here — so the conflict was the version
            // name. **This is the only way to tell the two apart**: `ON CONFLICT … DO NOTHING` does
            // not say which constraint refused, and catching the violation would abort the
            // transaction (ADR 0005's warning, one constraint over). Looking is safe because the
            // skill row's lock is still held, so nothing can insert that digest between the two
            // statements. A nameless version cannot reach here: NULL never equals NULL, so it can
            // only ever conflict on the digest.
            throw new VersionNameTakenException("'" + skill.name() + "' already has a version named "
                    + skill.version() + " with different content; declare a new version in "
                    + "SKILL.md and submit again");
        }

        // Read back rather than trusting the inputs: on a replay the authoritative submitted_at and
        // version name are the row's, so a client comparing either would otherwise see them move.
        VersionRepository.VersionRow row =
                versions.findBySkillAndDigest(skillId, manifest.digest(), false)
                        .orElseThrow(() -> new IllegalStateException(
                                "version for digest " + manifest.digest() + " vanished mid-submit"));

        // The version set has changed — or the same set has been asked about again — and either way
        // the card's "what is waiting" is this skill's draft side now (V21). M12 writes it because
        // it owns the row; M7 computes it because it owns the versions.
        metadata.recordSubmission(skillId, draftProjectionOf(skillId));

        // A first submission is one where this call inserted the version *and* it is the only one —
        // which is exactly "this call created the skill", since a skill comes into being with its
        // first version. Asked only on the insert branch: on a replay the skill plainly already
        // existed. Counting rather than trusting an upsert's own report, because `findOrCreate`
        // cannot say whether it inserted without a trick this does not need.
        boolean skillCreated = inserted.isPresent() && versions.countFor(skillId) == 1;

        return new SubmitOutcome(skillId, row.version(), row.digest(), row.fileCount(),
                row.totalBytes(), row.submittedAt(), inserted.isPresent(), skillCreated, row.state());
    }

    /**
     * The version a pin names, or empty when it names nothing.
     *
     * <p>For the two operations that act on <em>one</em> version, so {@link VersionPin.Latest} is
     * refused rather than resolved: "whatever is current" is not an instruction to publish or discard
     * something.
     */
    private Optional<VersionRepository.VersionRow> versionFor(String skillId, VersionPin pin,
            boolean liveOnly) {
        return switch (pin) {
            case VersionPin.Named(String version) ->
                    versions.findBySkillAndVersion(skillId, version, liveOnly);
            case VersionPin.Digest(String sha256Hex) ->
                    versions.findBySkillAndDigest(skillId, sha256Hex, liveOnly);
            case VersionPin.Latest() -> throw new IllegalArgumentException(
                    "publishing or discarding names one version; 'latest' does not name one");
        };
    }

    /**
     * How a pin reads in a message a person will see.
     *
     * <p>The name when there is one, and enough of the digest to recognise it when there is not — a
     * nameless version is ordinary (ADR 0033), and "version null of 'pdf-tools'" would be a message
     * about the wrong thing. Truncated the way a repository host abbreviates a commit.
     */
    private static String describe(VersionPin pin) {
        return switch (pin) {
            case VersionPin.Named(String version) -> "version " + version;
            case VersionPin.Digest(String sha256Hex) -> "version sha256:" + sha256Hex.substring(0, 8);
            case VersionPin.Latest() -> "the current version";
        };
    }

    /**
     * Makes one of a skill's versions the one consumers get, and marks it published.
     *
     * <p>Two writes, and the order matters: the version stops being a draft first, so the pointer
     * never names something that is not published even for the inside of this transaction. The
     * pointer itself is M12's to write (ADR 0053); this method hands it the version's facts.
     *
     * @return what happened, or empty when no version matches that pin
     * @throws VersionStateException when the version exists but has been discarded
     */
    public Optional<PromotionOutcome> publishVersion(Access access, VersionPin pin) {
        String at = Timestamps.now();

        if (!access.atLeast(GrantRole.MAINTAIN)) {
            // A 403 rather than a 404 because the caller can read this skill — it was in their
            // listing. See NotPermittedException.
            throw new NotPermittedException("publishing '" + access.name() + "' needs the "
                    + "`maintain` rung; `write` submits versions but does not put them live");
        }
        String name = access.name();
        String skillId = access.skillId();

        Optional<VersionRepository.VersionRow> found = versionFor(skillId, pin, false);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        VersionRepository.VersionRow version = found.get();

        if (VersionState.DISCARDED.equals(version.state())) {
            throw new VersionStateException(describe(pin) + " of '" + name
                    + "' was discarded and cannot be published");
        }

        if (version.id().equals(access.currentVersionId())) {
            // Already what consumers get. Nothing is written and there is nothing to audit: this is
            // the same state, reached twice. Rolling back onto an *older* published version is not
            // this case and does write.
            return Optional.of(new PromotionOutcome(skillId, version.version(), version.digest(),
                    version.stateAt(), false));
        }

        if (VersionState.DRAFT.equals(version.state())) {
            if (versions.markPublished(version.id(), at) == 0) {
                // Zero means the row stopped being a draft between the read above and this update,
                // and **two different transitions** do that: a concurrent discard, and a concurrent
                // publish of this same version — a double click on 上线, or a retry. The guard is
                // `state = 'draft'` and cannot tell them apart, so the row is read again and the
                // answer is decided on what it now says rather than on what the zero might have meant.
                VersionRepository.VersionRow now = versionFor(skillId, pin, false)
                        .orElseThrow(() -> new IllegalStateException(describe(pin) + " of '"
                                + name + "' vanished while being published"));
                if (VersionState.DISCARDED.equals(now.state())) {
                    // Refusing beats moving the pointer onto something the author has just thrown away.
                    throw new VersionStateException(describe(pin) + " of '" + name
                            + "' was discarded while being published");
                }
                // Published by the request that won: this call is the other half of the same intent
                // and finishes it, exactly as the rollback path below does. Its own time is reported,
                // not this request's — the version went live when the winner wrote it.
                version = now;
            }
        }
        // A version already published and no longer current is **rollback**, and only the pointer
        // moves: its state is what was asked for and its state_at is when it first went live, and
        // neither is this call's to change. Marking it published again is a no-op the guard refuses,
        // which is correct — reading that zero as a lost race is not, and it is what this branch
        // exists to prevent. The gateway hits it whenever its source reverts.

        // `changed` is what the statement did, not what this method expected it to do. The pointer
        // comparison above is against a value read earlier in this transaction, so it is a guess;
        // the update is the fact. When the pointer already names this version — the loser of a race
        // to publish it, most often — nothing is written and the caller must not audit a change
        // that did not happen.
        VersionRepository.VersionRow published = version;
        boolean moved = metadata.recordPublish(skillId,
                new VersionProjection(published.id(), published.version(), published.digest(),
                        published.submittedAt(), published.title(), published.description(),
                        published.frontmatter()),
                draftProjectionOf(skillId), at);

        // stateAt is this version's first time, which for a version just marked published is now.
        // Re-publishing an already-published version keeps the original, which is why the stored
        // column wins when it is set.
        String liveAt = published.stateAt() != null ? published.stateAt() : at;
        return Optional.of(new PromotionOutcome(skillId, published.version(), published.digest(),
                liveAt, moved));
    }

    /**
     * Marks a draft discarded, which is one-way: nothing publishes it afterwards.
     *
     * <p>Only a draft may be discarded. A published version may be pinned by an address somebody
     * else already holds — {@code @1.2.3} has to keep resolving — so taking it away is not this
     * operation's business.
     *
     * @return the id of the skill it happened to, or empty when no version matches that pin. The
     *         id rather than the version, because the caller audits this and an audit row names the
     *         skill by its identity (ADR 0004)
     * @throws VersionStateException when the version exists but is not a draft
     */
    public Optional<String> discardVersion(Access access, VersionPin pin) {
        String at = Timestamps.now();
        String name = access.name();
        String skillId = access.skillId();

        Optional<VersionRepository.VersionRow> found = versionFor(skillId, pin, false);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        // The ladder's line, written as one condition: `maintain` may discard anybody's draft, and
        // `write` may discard only its own (ADR 0034 §决定 5, as lifted into ADR 0047). The owner
        // is above `maintain`, so ownership falls out of the first half rather than being a third
        // branch. `submitted_by` is the only column that can tell whose a draft is.
        boolean mine = access.callerId().equals(found.get().submittedBy());
        if (!access.atLeast(GrantRole.MAINTAIN)
                && !(access.atLeast(GrantRole.WRITE) && mine)) {
            throw new NotPermittedException("'" + name + "' was submitted by somebody else; "
                    + "the `write` rung discards your own drafts, and discarding other people's "
                    + "needs `maintain`");
        }
        if (!VersionState.DRAFT.equals(found.get().state())) {
            throw new VersionStateException(describe(pin) + " of '" + name + "' is "
                    + found.get().state() + "; only a draft can be discarded");
        }

        if (versions.markDiscarded(found.get().id(), at) == 0) {
            // Same misreading this method's sibling had, and the same fix. The guard is
            // `state = 'draft'`, and zero means the row stopped being a draft between the read above
            // and this update — which a concurrent *discard of this same version* does just as
            // readily as a concurrent publish. Reading only the zero told a double click on 丢弃 that
            // its version had just been published, which is both false and alarming.
            VersionRepository.VersionRow now = versionFor(skillId, pin, false)
                    .orElseThrow(() -> new IllegalStateException(describe(pin) + " of '"
                            + name + "' vanished while being discarded"));
            if (!VersionState.DISCARDED.equals(now.state())) {
                throw new VersionStateException(describe(pin) + " of '" + name
                        + "' was published while being discarded");
            }
            // Discarded by the request that won. The state this call asked for is the state there is,
            // so it succeeds — discarding is idempotent in the same way publishing is.
        }

        metadata.recordDiscard(skillId, draftProjectionOf(skillId));
        return Optional.of(skillId);
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
        return versionOf(access, pin, true).map(version -> toSnapshot(access, version));
    }

    /**
     * One of a skill's versions for the author plane, at the {@code browse} rung or above.
     *
     * <p><strong>The author plane is per rung since ADR 0051.</strong> Detail, body and a single
     * file are {@code browse}; a draft is {@code write} and up, because a draft is a version you
     * could act on. This entry point stops at the first of those lines, and
     * {@link #authorDraftSnapshot} is the one that crosses the second.
     *
     * <p><strong>Visible but below the rung is a 403, not a 404.</strong> The caller can read this
     * skill — it is in their listing or it was handed to them by the public rung — so pretending it
     * does not exist would be false, and {@link NotPermittedException} is how the service says
     * "you may see it, this rung is not enough" (the same answer the write paths give).
     */
    public Optional<SkillSnapshot> authorSnapshot(Access access, VersionPin pin) {
        return authorSnapshotAtLeast(access, pin, GrantRole.BROWSE);
    }

    /**
     * One of a skill's versions, drafts included — the {@code write} rung and above.
     *
     * <p>Separate from {@link #authorSnapshot} rather than a flag, for the reason the two planes
     * were separated in the first place: a caller that can read published content must not be able
     * to reach drafts by passing a boolean. The diff is the only reader today (ADR 0051 §决定 2).
     */
    public Optional<SkillSnapshot> authorDraftSnapshot(Access access, VersionPin pin) {
        return authorSnapshotAtLeast(access, pin, GrantRole.WRITE);
    }

    private Optional<SkillSnapshot> authorSnapshotAtLeast(Access access, VersionPin pin,
            String required) {
        if (!access.atLeast(required)) {
            throw new NotPermittedException("reading '" + access.name() + "' at this level needs "
                    + "the `" + required + "` rung");
        }
        // Everyone below `write` sees published versions only, even when the address pins one:
        // a draft resolves to nothing for them, exactly as it does on the consumption plane.
        boolean publishedOnly = !access.atLeast(GrantRole.WRITE);
        return versionOf(access, pin, publishedOnly).map(version -> toSnapshot(access, version));
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
        boolean mayWrite = access.atLeast(GrantRole.WRITE);

        Optional<VersionRepository.VersionRow> chosen = versionOf(access, pin, !mayWrite);
        if (chosen.isEmpty() && !(pin instanceof VersionPin.Latest)) {
            // An explicit pin that resolves to nothing is §4.1's one 404. `latest` without a
            // published version is the empty-tree case above, not a miss.
            return Optional.empty();
        }

        SkillSnapshot selected = chosen.map(version -> toSnapshot(access, version)).orElse(null);
        List<VersionSummary> visible = mayWrite
                ? versions.versionsOf(access.skillId(), access.currentVersionId())
                : versions.publishedVersionsOf(access.skillId(), access.currentVersionId());

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
                access.atLeast(GrantRole.BROWSE)));
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
    private Optional<VersionRepository.VersionRow> versionOf(Access access, VersionPin pin,
            boolean liveOnly) {
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
            return liveOnly
                    ? Optional.empty()
                    : versions.findNewestNotDiscarded(access.skillId())
                            .or(() -> versions.findNewest(access.skillId()));
        }
        return switch (pin) {
            case VersionPin.Latest() -> Optional.of(versions.findCurrent(access.currentVersionId())
                    .orElseThrow(() -> new IllegalStateException("skill " + access.skillId()
                            + " points at version " + access.currentVersionId()
                            + ", which is not a published version")));
            case VersionPin.Named(String version) ->
                    versions.findBySkillAndVersion(access.skillId(), version, liveOnly);
            case VersionPin.Digest(String sha256Hex) ->
                    versions.findBySkillAndDigest(access.skillId(), sha256Hex, liveOnly);
        };
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
    private SkillSnapshot toSnapshot(Access access, VersionRepository.VersionRow version) {
        return new SkillSnapshot(
                access.skillId(), access.namespaceId(), access.name(), version.title(),
                version.description(), version.frontmatter(), version.version(), version.digest(),
                version.fileCount(), version.totalBytes(), version.state(), version.stateAt(),
                version.id().equals(access.currentVersionId()), versions.filesOf(version.id()));
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

    private DraftProjection draftProjectionOf(String skillId) {
        return versions.draftProjectionOf(skillId).orElse(DraftProjection.empty());
    }
}
