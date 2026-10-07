package com.skillmasterai.modules.version;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.version.internal.BlobGc;
import com.skillmasterai.modules.version.internal.SkillRepository;
import com.skillmasterai.modules.version.internal.VersionRepository;
import java.util.List;
import java.util.Optional;

/**
 * M7: the skill's identity, its immutable versions, and the pointer between them.
 *
 * <p><strong>Submitting and publishing are separate operations here</strong> (ADR 0031). Submitting
 * records a version and stops; the pointer does not move and the skill row does not change, so
 * nothing the consumption plane can see has changed. Publishing is what moves the pointer, and only
 * the browser plane reaches it.
 *
 * <p><strong>This class never opens a transaction.</strong> §2.5 rule 2 puts cross-module
 * transaction boundaries in the use-case layer, and both operations are nothing but cross-module —
 * so every method here is written to be called inside a transaction the caller owns. Running one
 * outside a transaction would still work, and would be wrong: the version row, its files and the
 * pointer would each commit separately.
 */
public final class SkillVersionService {

    private final SkillRepository skills;
    private final VersionRepository versions;
    private final BlobGc blobGc;

    public SkillVersionService(SkillRepository skills, VersionRepository versions, BlobGc blobGc) {
        this.skills = skills;
        this.versions = versions;
        this.blobGc = blobGc;
    }

    /**
     * Records a version of the named skill from an already-stored manifest, as a draft.
     *
     * <p>The blobs are stored by the caller before this is called: M7 deals in {@link Manifest},
     * which carries digests and sizes, and never touches bytes. That keeps the
     * {@link com.skillmasterai.modules.blob.BlobStore} seam the only way to reach content.
     *
     * <p>Everything here is what submitting has always done except the last step it no longer takes.
     * The version is inserted, its manifest written, and the sweep run — but the pointer stays where
     * it is, and the skill row keeps the metadata of whatever is currently published. A skill that
     * has never been published has no current version at all.
     *
     * @throws SkillDeletedException if the name belongs to a soft-deleted skill
     */
    public SubmitOutcome submit(String namespaceId, SkillMetadata metadata, Manifest manifest,
            String submittedBy, String source) {
        // Before anything writes version_file, and unconditionally — including the replay that
        // inserts nothing, which still sweeps. See BlobGc.beginExclusiveWrite.
        blobGc.beginExclusiveWrite();

        String at = Timestamps.now();

        String skillId = skills.findOrCreate(namespaceId, metadata, submittedBy, at)
                .orElseThrow(() -> new SkillDeletedException(metadata.name()));

        return record(skillId, metadata, manifest, submittedBy, source, at);
    }

    /**
     * Records a version of a skill that already exists, as a draft — the {@code editor} path
     * (ADR 0034).
     *
     * <p><strong>It cannot create a skill, and that is the point of having it at all.</strong>
     * Submitting into somebody else's namespace is not expressible through {@link #submit}: the
     * target there comes from the token's subject and never from the request, which is what keeps a
     * submission from being a cross-namespace write primitive. Here the target <em>is</em> named, so
     * the one thing that makes that safe is that it can only ever add a version to a skill the caller
     * has already been given write access to — never bring one into being. Creatable names stay
     * confined to namespaces the caller owns.
     *
     * @return what was recorded, or empty when there is no such skill for this caller
     * @throws NotPermittedException when they may read it and may not write it
     */
    public Optional<SubmitOutcome> submitVersion(String namespaceId, String name,
            SkillMetadata metadata, Manifest manifest, String submittedBy, String source,
            Caller caller) {
        blobGc.beginExclusiveWrite();

        Optional<SkillRepository.Permitted> resolved = skills.permitted(namespaceId, name, caller);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        if (!resolved.get().mayWrite()) {
            throw new NotPermittedException("'" + name + "' was shared with you as a reader; "
                    + "submitting needs an editor grant");
        }
        return Optional.of(record(resolved.get().skill().id(), metadata, manifest, submittedBy,
                source, Timestamps.now()));
    }

    /**
     * The half of a submission both entry points share: insert or recognise, write the manifest,
     * sweep, and read back.
     *
     * <p>Extracted when {@link #submitVersion} arrived, and the extraction is the honest one — the
     * two differ in how the skill is found and in nothing else. The alternative was a boolean
     * "createIfMissing" on {@link #submit}, which would have made "may this call bring a skill into
     * being" a literal at the call site, and it is the one question that must not be answerable by
     * editing a literal.
     */
    private SubmitOutcome record(String skillId, SkillMetadata metadata, Manifest manifest,
            String submittedBy, String source, String at) {
        Optional<String> inserted =
                versions.insertIfAbsent(skillId, metadata, manifest.digest(), manifest.fileCount(),
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
            throw new VersionNameTakenException("'" + metadata.name() + "' already has a version named "
                    + metadata.version() + " with different content; declare a new version in "
                    + "SKILL.md and submit again");
        }

        // Read back rather than trusting the inputs: on a replay the authoritative submitted_at and
        // version name are the row's, so a client comparing either would otherwise see them move.
        VersionRepository.VersionRow row =
                versions.findBySkillAndDigest(skillId, manifest.digest(), false)
                        .orElseThrow(() -> new IllegalStateException(
                                "version for digest " + manifest.digest() + " vanished mid-submit"));

        blobGc.sweep();

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
     * something. Nothing reaches it — the request bodies carry the same suffix grammar as an address,
     * and that grammar has no spelling for latest — so it is a programming error, and says so.
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
     * never names something that is not published even for the inside of this transaction.
     *
     * @return what happened, or empty when there is no such skill in that namespace or no version
     *         matching that pin — one answer, because §4.1 gives an address that resolves to nothing
     *         one answer
     * @throws VersionStateException when the version exists but has been discarded
     */
    public Optional<PromotionOutcome> publishVersion(String namespaceId, String name,
            VersionPin pin, Caller caller) {
        String at = Timestamps.now();

        Optional<SkillRepository.Permitted> resolved = skills.permitted(namespaceId, name, caller);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        SkillRepository.Permitted permitted = resolved.get();
        if (!permitted.mayAdminister()) {
            // The first 403 in the system, and it is not a 404 because the caller can read this
            // skill — it was in their listing. See NotPermittedException.
            throw new NotPermittedException("publishing '" + name + "' is its owner's to do; an "
                    + "editor grant allows submitting versions and discarding your own drafts");
        }
        SkillRepository.SkillRow row = permitted.skill();

        Optional<VersionRepository.VersionRow> found = versionFor(row.id(), pin, false);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        VersionRepository.VersionRow version = found.get();

        if (VersionState.DISCARDED.equals(version.state())) {
            throw new VersionStateException(describe(pin) + " of '" + name
                    + "' was discarded and cannot be published");
        }

        if (version.id().equals(row.currentVersionId())) {
            // Already what consumers get. Nothing is written and there is nothing to audit: this is
            // the same state, reached twice. Rolling back onto an *older* published version is not
            // this case and does write.
            return Optional.of(new PromotionOutcome(row.id(), version.version(), version.digest(),
                    version.stateAt(), false));
        }

        if (VersionState.DRAFT.equals(version.state())) {
            if (versions.markPublished(version.id(), at) == 0) {
                // Zero means the row stopped being a draft between the read above and this update,
                // and **two different transitions** do that: a concurrent discard, and a concurrent
                // publish of this same version — a double click on 上线, or a retry. The guard is
                // `state = 'draft'` and cannot tell them apart, so the row is read again and the
                // answer is decided on what it now says rather than on what the zero might have meant.
                VersionRepository.VersionRow now = versionFor(row.id(), pin, false)
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
        // comparison above is against a row read earlier in this transaction, so it is a guess; the
        // update is the fact. When the pointer already names this version — the loser of a race to
        // publish it, most often — nothing is written and the caller must not audit a change that
        // did not happen. See SkillRepository#moveCurrentVersion.
        boolean moved = skills.moveCurrentVersion(row.id(), version.id(), at);

        // stateAt is this version's first time, which for a version just marked published is now.
        // Re-publishing an already-published version keeps the original, which is why the stored
        // column wins when it is set.
        String liveAt = version.stateAt() != null ? version.stateAt() : at;
        return Optional.of(new PromotionOutcome(row.id(), version.version(), version.digest(),
                liveAt, moved));
    }

    /**
     * Marks a draft discarded, which is one-way: nothing publishes it afterwards.
     *
     * <p>Only a draft may be discarded. A published version may be pinned by an address somebody
     * else already holds — {@code @1.2.3} has to keep resolving — so taking it away is not this
     * operation's business.
     *
     * @return the id of the skill it happened to, or empty when there is no such skill or no version
     *         matching that pin. The id rather than the version, because the caller audits this and
     *         an audit row names the skill by its identity (ADR 0004) — the same shape
     *         {@link #softDelete} returns, for the same reason
     * @throws VersionStateException when the version exists but is not a draft
     */
    public Optional<String> discardVersion(String namespaceId, String name, VersionPin pin,
            Caller caller) {
        String at = Timestamps.now();

        Optional<SkillRepository.Permitted> resolved = skills.permitted(namespaceId, name, caller);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        SkillRepository.Permitted permitted = resolved.get();
        SkillRepository.SkillRow skill = permitted.skill();

        Optional<VersionRepository.VersionRow> found = versionFor(skill.id(), pin, false);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        // "An editor may discard their own drafts" (ADR 0034 §决定 5), and `submitted_by` is the
        // only column that can tell whose a draft is. Discarding is one-way and the owner may be
        // midway through the draft this would throw away, so the owner's versions are the owner's
        // to decide about.
        if (!permitted.owns() && !caller.userId().equals(found.get().submittedBy())) {
            throw new NotPermittedException("'" + name + "' was submitted by somebody else; "
                    + "an editor grant lets you discard your own drafts, not other people's");
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
            VersionRepository.VersionRow now = versionFor(skill.id(), pin, false)
                    .orElseThrow(() -> new IllegalStateException(describe(pin) + " of '"
                            + name + "' vanished while being discarded"));
            if (!VersionState.DISCARDED.equals(now.state())) {
                throw new VersionStateException(describe(pin) + " of '" + name
                        + "' was published while being discarded");
            }
            // Discarded by the request that won. The state this call asked for is the state there is,
            // so it succeeds — discarding is idempotent in the same way publishing is.
        }
        return Optional.of(skill.id());
    }

    /**
     * One of a skill's published versions, for the consumption plane, or empty when there is
     * nothing that plane may read.
     *
     * <p>The namespace is a required parameter rather than something the caller checks afterwards,
     * which is what makes an unreadable skill indistinguishable from an absent one: see
     * {@link SkillRepository#permitted}.
     *
     * <p><strong>Three conditions, all checked.</strong> The skill must not be soft-deleted, the
     * version must exist, <em>and</em> it must be published. Resolving the version row alone would
     * keep serving a soft-deleted skill, because a soft delete leaves {@code skill_version} and
     * {@code version_file} in place (§3.3 point 5) — the mistake ADR 0012's 后果 section names.
     * Dropping the third condition would serve a draft, which is ADR 0031's version of the same
     * mistake.
     *
     * @param namespaceId the namespace the caller is allowed to read from
     * @param pin         which version. {@link VersionPin.Latest} re-reads the pointer on every
     *                    call, so it drifts as soon as someone publishes; the other two never do
     */
    public Optional<SkillSnapshot> liveSnapshot(String namespaceId, String name, VersionPin pin,
            Caller caller) {
        return snapshot(namespaceId, name, pin, true, caller);
    }

    /**
     * One of a skill's versions, for the author, including drafts.
     *
     * <p>The same resolution with the published-only predicate dropped, which is the whole of what
     * the author plane adds. Kept as a second named entry point rather than a flag on one method so
     * that a consumption-plane caller cannot reach for the wider one by passing a boolean.
     */
    public Optional<SkillSnapshot> authorSnapshot(String namespaceId, String name, VersionPin pin,
            Caller caller) {
        return snapshot(namespaceId, name, pin, false, caller);
    }

    /**
     * @param liveOnly doubles as the choice of access predicate, and that is not a coincidence: the
     *                 consumption plane is the one that may only <em>read</em>, and the author plane
     *                 is the one that may write — seeing a draft is seeing something you could act
     *                 on. Keeping the two questions apart would let a caller ask for drafts with a
     *                 reader's grant (ADR 0034 §决定 6)
     */
    private Optional<SkillSnapshot> snapshot(String namespaceId, String name, VersionPin pin,
            boolean liveOnly, Caller caller) {
        Optional<SkillRepository.Permitted> resolved = skills.permitted(namespaceId, name, caller);
        // The author plane asks a second question on top of "may I see it": drafts are versions you
        // could act on, so seeing them is a write-level thing. A viewer grant reaches neither.
        if (resolved.isEmpty() || (!liveOnly && !resolved.get().mayWrite())) {
            return Optional.empty();
        }
        SkillRepository.SkillRow row = resolved.get().skill();
        return versionOf(row, pin, liveOnly).map(version -> toSnapshot(row, version));
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
    private Optional<VersionRepository.VersionRow> versionOf(SkillRepository.SkillRow row,
            VersionPin pin, boolean liveOnly) {
        if (row.currentVersionId() == null && pin instanceof VersionPin.Latest) {
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
                    : versions.findNewestNotDiscarded(row.id()).or(() -> versions.findNewest(row.id()));
        }
        return switch (pin) {
            case VersionPin.Latest() -> Optional.of(versions.findCurrent(row.currentVersionId())
                    .orElseThrow(() -> new IllegalStateException("skill " + row.id()
                            + " points at version " + row.currentVersionId()
                            + ", which is not a published version")));
            case VersionPin.Named(String version) ->
                    versions.findBySkillAndVersion(row.id(), version, liveOnly);
            case VersionPin.Digest(String sha256Hex) ->
                    versions.findBySkillAndDigest(row.id(), sha256Hex, liveOnly);
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
    private SkillSnapshot toSnapshot(SkillRepository.SkillRow row,
            VersionRepository.VersionRow version) {
        return new SkillSnapshot(
                row.id(), row.namespaceId(), row.name(), version.title(), version.description(),
                version.frontmatter(), row.visibility(), version.version(), version.digest(),
                version.fileCount(), version.totalBytes(), version.state(), version.stateAt(),
                version.id().equals(row.currentVersionId()), versions.filesOf(version.id()));
    }

    /**
     * Every version of a skill, newest first, for the author's own list.
     *
     * <p>Deliberately unfiltered: it is the list a person chooses from, so a discarded version is
     * shown as discarded rather than made to look like it never existed, and a superseded published
     * version still appears because it is still addressable — somebody may hold {@code @3}.
     *
     * <p>An empty list means there is no such skill in that namespace, or it is soft-deleted: a skill
     * without versions cannot exist, since submitting writes both rows in one transaction.
     */
    public List<VersionSummary> versionsOf(String namespaceId, String name) {
        return versions.versionsOf(namespaceId, name);
    }

    /**
     * The versions a consumer may invoke, or empty when this caller has nothing to invoke.
     *
     * <p>Two conditions, and the second is the one worth stating. The caller must be permitted — the
     * same {@code permitted} the snapshots use, which is what keeps "not yours" and "no such skill"
     * one outcome — <em>and</em> at least one version must be published. <strong>An empty list is
     * not the answer for the second case.</strong> A skill made only of drafts answers 404
     * everywhere else on this plane ({@link #liveSnapshot}'s {@code Latest} with no pointer is
     * exactly that), so an empty list here would be this one entry point saying "it exists, there is
     * nothing in it" where the rest of the plane says "there is no such thing" — one address, two
     * meanings.
     */
    public Optional<List<VersionSummary>> liveVersionsOf(String namespaceId, String name,
            Caller caller) {
        if (skills.permitted(namespaceId, name, caller).isEmpty()) {
            return Optional.empty();
        }
        List<VersionSummary> published = versions.publishedVersionsOf(namespaceId, name);
        return published.isEmpty() ? Optional.empty() : Optional.of(published);
    }

    /**
     * Soft-deletes a skill by name, and reports which one it was.
     *
     * <p>The sweep runs unconditionally, exactly as it does on submit: §3.3 point 5 requires
     * reclamation to happen in the same transaction as the version change that caused it, and a soft
     * delete is such a change — even though in P0 it orphans nothing, because every version survives.
     *
     * @return the deleted skill's id, or empty when no live skill of that name is in that namespace
     */
    public Optional<String> softDelete(String namespaceId, String name, Caller caller) {
        // Taken first for the same reason as on submit, even though this path writes no
        // version_file row of its own: the sweep is here, and it has to be exclusive against a
        // concurrent submit rather than only against another delete. See BlobGc.beginExclusiveWrite.
        Optional<SkillRepository.Permitted> resolved = skills.permitted(namespaceId, name, caller);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        if (!resolved.get().mayAdminister()) {
            throw new NotPermittedException("deleting '" + name + "' is its owner's to do; it takes "
                    + "the skill away from everyone reading it, and only the owner can answer for that");
        }

        blobGc.beginExclusiveWrite();

        Optional<String> deleted = skills.softDelete(namespaceId, name, caller, Timestamps.now());
        blobGc.sweep();
        return deleted;
    }
}
