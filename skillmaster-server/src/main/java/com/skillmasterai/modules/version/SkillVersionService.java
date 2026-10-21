package com.skillmasterai.modules.version;

import com.skillmasterai.common.ConflictException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
     * {@link BlobGc#beginExclusiveWrite} must come before a submit or a soft delete writes
     * {@code version_file} — the lock's own note explains the deadlock a later request causes. A
     * draft <em>file write</em> does not use this wrapper: it touches one row, does not sweep, and
     * is protected from a concurrent sweep by the GC's grace period (v7 TD §blob 回收). And the
     * skill row's lock (taken by M12's {@code findOrCreate}, inside this section) must come
     * <em>after</em> this one rather than before — otherwise a submit would hold the row while
     * waiting for the GC lock that a soft delete already holds, and the two would deadlock. The use
     * case therefore puts both the address creation and the version write inside one call to this
     * method.
     */
    public <T> T underExclusiveWrite(Supplier<T> change) {
        blobGc.beginExclusiveWrite();
        T result = change.get();
        blobGc.sweep();
        return result;
    }

    /**
     * The gates every draft write passes before it may take a lock (v7 TD §并发).
     *
     * <p>⓪ is the caller's: the use case resolved {@code accessFor} and answered 404 already. This
     * method owns ①–③ and the frozen-content rule, in this order:
     * <ol>
     *   <li>the entry rung — below {@code write} is a 403, because the caller can see the skill;</li>
     *   <li>state visibility — a draft, or a published row this rung may read; discarded and revoked
     *       rows are invisible to {@code write} and answer 404;</li>
     *   <li>the content rule — anything not a draft is a 409 that names the state, because a write
     *       aimed at frozen content is a different request, not a missing one;</li>
     *   <li>ownership — a {@code write} caller may change only a draft they submitted.</li>
     * </ol>
     *
     * <p><strong>Deliberately not locked.</strong> This is the decision the lock waits on, so it has
     * to happen before the lock is taken; the caller re-reads the row under {@link #lockDraft} and
     * decides again on what it then says.
     *
     * @return the row, or empty when the pin names nothing this rung may see
     */
    public Optional<VersionSummary> openDraft(Access access, String rowId) {
        if (!access.atLeast(GrantRole.WRITE)) {
            throw new NotPermittedException("changing '" + access.name() + "' files needs the "
                    + "`write` rung");
        }
        Optional<VersionRepository.VersionRow> found =
                versions.findByRowId(access.skillId(), rowId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        VersionRepository.VersionRow row = found.get();
        if (!visibleTo(access, row.state())) {
            // A `write` caller holding a discarded row's id learns nothing — the same 404 as an id
            // that names nothing, which is what "not visible to this rung" means everywhere else.
            return Optional.empty();
        }
        if (!VersionState.DRAFT.equals(row.state())) {
            throw notDraft(access, row, "this row is ");
        }
        boolean mine = access.callerId().equals(row.submittedBy());
        if (!access.atLeast(GrantRole.MAINTAIN) && !mine) {
            throw new NotPermittedException("'" + access.name() + "' was submitted by somebody "
                    + "else; the `write` rung changes your own drafts, and changing other people's "
                    + "needs `maintain`");
        }
        return Optional.of(summary(access, row));
    }

    /**
     * The same row for a read that is draft-only — {@code grep} (v7 做 #5).
     *
     * <p>Different from {@link #openDraft} in exactly one way: reading a draft's content is
     * something every {@code write} holder may do, while <em>changing</em> it is limited to the
     * submitter. Ownership therefore does not appear here. What does appear is the state rule: grep
     * has no meaning on frozen content, and the answer is a 409 that says so rather than the 404 a
     * missing version would get (v7 TD §grep 的作用域与上限).
     */
    public Optional<VersionSummary> openDraftForRead(Access access, String rowId) {
        if (!access.atLeast(GrantRole.WRITE)) {
            throw new NotPermittedException("searching '" + access.name() + "' files needs the "
                    + "`write` rung");
        }
        Optional<VersionRepository.VersionRow> found =
                versions.findByRowId(access.skillId(), rowId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        VersionRepository.VersionRow row = found.get();
        if (!visibleTo(access, row.state())) {
            return Optional.empty();
        }
        if (!VersionState.DRAFT.equals(row.state())) {
            throw new ConflictException(
                    com.skillmasterai.common.ErrorCode.GREP_REQUIRES_DRAFT,
                    "grep only applies to a draft; this row is " + row.state()
                            + ". For a published version, read the file and grep it locally.",
                    List.of(new com.skillmasterai.common.ApiError.Detail("state", row.state())));
        }
        return Optional.of(summary(access, row));
    }

    /**
     * The row, locked, re-checking the content rule on what it now says.
     *
     * <p>Called inside the write's transaction after {@link #openDraft}. A row that stopped being a
     * draft between the two is a concurrent publish or discard, and the honest answer is the same 409
     * the unlocked check would have given.
     */
    public VersionSummary lockDraft(Access access, String rowId) {
        VersionRepository.VersionRow row = versions.findByRowIdForUpdate(access.skillId(), rowId)
                .orElseThrow(() -> new IllegalStateException("version " + rowId + " of skill "
                        + access.skillId() + " vanished while being written"));
        if (!VersionState.DRAFT.equals(row.state())) {
            throw notDraft(access, row, "this row is now ");
        }
        return summary(access, row);
    }

    /** A row's manifest, as the write path reads it after its own change. */
    public Manifest filesOf(VersionSummary row) {
        return versions.filesOf(row.id());
    }

    public void putFile(String versionId, ManifestEntry entry) {
        versions.putFile(versionId, entry);
    }

    public int deleteFile(String versionId, String relpath) {
        return versions.deleteFile(versionId, relpath);
    }

    public int renameFile(String versionId, String from, String to) {
        return versions.renameFile(versionId, from, to);
    }

    public void copyFile(String versionId, ManifestEntry source, String to) {
        versions.copyFile(versionId, source, to);
    }

    /**
     * Applies a finished file change to the row, or reports why it may not.
     *
     * <p>The order is the whole method: compare the digest first (an unchanged manifest is a no-op,
     * 200, and writes nothing), then ask whether the content belongs to <em>another</em> version of
     * this skill, then write. The ordering is what makes "write the same bytes again" a no-op rather
     * than a 409 against itself — {@code findBySkillAndDigest} returns the current row too, so the
     * check excludes it explicitly.
     *
     * @param metadata the new title/description/frontmatter when the write touched the root
     *                 {@code SKILL.md}, or null
     * @return the row as it now reads; the locked row unchanged on a no-op
     * @throws ConflictException {@code content_already_exists} when another version holds this content
     */
    public VersionSummary commitContent(Access access, VersionSummary locked, Manifest next,
            SkillMetadata metadata) {
        String digest = next.digest();
        if (digest.equals(locked.digest())) {
            return locked;
        }
        // **The check and the write are one critical section per skill.** Without the lock two
        // drafts of the same skill can both pass the check and race to UNIQUE (skill_id, digest);
        // PostgreSQL then aborts one transaction, and the caller would see a 500 for what is a 409.
        // The lock is keyed by skill id, so different skills never queue behind it (TD §性能预算).
        versions.lockContentOf(access.skillId());
        Optional<VersionRepository.VersionRow> other =
                versions.findBySkillAndDigest(access.skillId(), digest, false)
                        .filter(row -> !row.id().equals(locked.id()));
        if (other.isPresent()) {
            throw contentConflict(access, other.get());
        }
        if (versions.updateContent(locked.id(), digest, next.fileCount(), next.totalBytes(),
                metadata) == 0) {
            throw new ConflictException(
                    com.skillmasterai.common.ErrorCode.VERSION_IS_NOT_DRAFT,
                    "write only applies to a draft; this row changed state while being written");
        }
        // The skill card's "waiting" count is this version set (V21), and the set has changed.
        refreshDraftProjection(access.skillId());
        return versions.findByRowId(access.skillId(), locked.id())
                .map(row -> summary(access, row))
                .orElseThrow(() -> new IllegalStateException(
                        "version " + locked.id() + " vanished after a write"));
    }

    /**
     * One answer for "this content is already another version's", from either path.
     *
     * <p>The pin is only named when the caller may see that state: a `write` caller cannot read a
     * discarded or delisted row, and naming its id in a 409 would hand them an address the next
     * request refuses (v7 门序 ②). The state is always named, because it is why the content is not
     * available rather than a fact about a row they can open.
     */
    /**
     * The refusal for a row that is not a draft any more.
     *
     * <p>The state is always named — it is why the write cannot happen. The pin is named only when
     * the caller can actually open that state: published is visible to every `write`+ caller, while
     * a revoked or discarded row is visible only to `maintain`+. Handing a `write` caller an id the
     * next request refuses would be the same information-boundary mistake the gate order avoids.
     */
    private ConflictException notDraft(Access access, VersionRepository.VersionRow row,
            String wording) {
        List<com.skillmasterai.common.ApiError.Detail> details = new ArrayList<>();
        details.add(new com.skillmasterai.common.ApiError.Detail("state", row.state()));
        if (VersionState.PUBLISHED.equals(row.state()) || access.atLeast(GrantRole.MAINTAIN)) {
            details.add(new com.skillmasterai.common.ApiError.Detail("pin", pinOf(row)));
        }
        return new ConflictException(com.skillmasterai.common.ErrorCode.VERSION_IS_NOT_DRAFT,
                "write only applies to a draft; " + wording + row.state(), details);
    }

    private ConflictException contentConflict(Access access, VersionRepository.VersionRow existing) {
        List<com.skillmasterai.common.ApiError.Detail> details = new ArrayList<>();
        if ("published".equals(existing.state())) {
            details.add(new com.skillmasterai.common.ApiError.Detail("pin", pinOf(existing)));
        }
        details.add(new com.skillmasterai.common.ApiError.Detail("state", existing.state()));
        // Every row has a version code, including a draft. Without it a draft collision is an
        // answer the page cannot name: there is no version number, and the version code is what
        // the page links to (v8 TD §内容重复).
        details.add(new com.skillmasterai.common.ApiError.Detail("code", existing.id()));
        return new ConflictException(com.skillmasterai.common.ErrorCode.CONTENT_ALREADY_EXISTS,
                "this content already exists as another version of '" + access.name() + "'",
                details);
    }

    private void refreshDraftProjection(String skillId) {
        metadata.recordSubmission(skillId, draftProjectionOf(skillId));
    }

    /** A row as the author list reads it — the shape a write echoes back. */
    private static VersionSummary summary(Access access, VersionRepository.VersionRow row) {
        return new VersionSummary(row.id(), row.version(), row.draftName(), row.note(),
                row.digest(), row.title(), row.fileCount(), row.totalBytes(), row.submittedAt(),
                row.submittedBy(), row.state(), row.stateAt(), row.firstPublishedAt(),
                row.revokedReason(), row.id().equals(access.currentVersionId()));
    }

    private static String pinOf(VersionRepository.VersionRow row) {
        if (row.version() != null) {
            return row.version();
        }
        if (VersionState.DRAFT.equals(row.state()) || VersionState.DISCARDED.equals(row.state())) {
            return row.id();
        }
        return "sha256:" + row.digest();
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
            String draftName, String note, String submittedBy, String source, String at) {
        return record(skillId, skill, manifest, draftName, note, submittedBy, source, at);
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
            Manifest manifest, String draftName, String note, String submittedBy, String source,
            String at) {
        if (!access.atLeast(GrantRole.WRITE)) {
            throw new NotPermittedException("'" + access.name() + "' is shared with you below the "
                    + "`write` rung; submitting versions needs `write`");
        }
        return Optional.of(record(access.skillId(), skill, manifest, draftName, note,
                submittedBy, source, at));
    }

    /**
     * The half of a submission both entry points share: insert or recognise, write the manifest,
     * read back, and rewrite the draft projection.
     *
     * <p>Extracted when {@link #submitVersion} arrived, and the extraction is the honest one — the
     * two differ in how the skill is found and in nothing else.
     */
    private SubmitOutcome record(String skillId, SkillMetadata skill, Manifest manifest,
            String draftName, String note, String submittedBy, String source, String at) {
        // The same per-skill critical section the file write takes. Without it a submission can
        // commit a digest between a write's check and its UPDATE, and that write would see a 500
        // where the contract says 409.
        //
        // **The absence of a deadlock comes from the table lock, not from a shared order.** A
        // submission holds `version_file`'s SHARE ROW EXCLUSIVE and a file write holds its ROW
        // EXCLUSIVE; the two are mutually exclusive, so a submission with the skill row and a write
        // with this advisory lock are never both in flight. That argument depends on every writer
        // taking the table lock first — do not add a write path that skips `version_file`, and do
        // not let submission stop taking the table lock.
        versions.lockContentOf(skillId);
        Optional<String> inserted =
                versions.insertIfAbsent(skillId, skill, draftName, note, manifest.digest(),
                        manifest.fileCount(), manifest.totalBytes(), source, submittedBy, at);

        if (inserted.isPresent()) {
            versions.insertFiles(inserted.get(), manifest);
        }
        // Nothing else to tell apart: the version name is no longer written at submission, so the
        // only unique constraint this insert can meet is the digest's, and DO NOTHING on a replay
        // is the answer rather than an error. A name collision now happens at publish, where the
        // number is chosen.

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

        return new SubmitOutcome(skillId, row.id(), row.number(), row.version(), row.draftName(),
                row.note(), row.digest(), row.fileCount(), row.totalBytes(), row.submittedAt(),
                inserted.isPresent(), skillCreated, row.state());
    }

    /**
     * Creates a draft from a base version plus one edit, and refuses content another version
     * already holds (v8 TD §底稿的读取与复制).
     *
     * <p>Unlike {@link #submitVersion}, a digest that already exists is <strong>not</strong> a
     * replay: this request is creating a row, and the row it collided with is the answer. So it
     * throws {@code content_already_exists} rather than returning the existing version.
     *
     * <p>Must be called inside {@link #underExclusiveWrite}; the caller also gives the id a chance
     * to be redrawn by going through {@code DraftRecorder}.
     */
    public VersionSummary submitFromBase(Access access, SkillMetadata skill, Manifest manifest,
            String draftName, String note, String submittedBy, String source, String at) {
        if (!access.atLeast(GrantRole.WRITE)) {
            throw new NotPermittedException("'" + access.name() + "' is shared with you below the "
                    + "`write` rung; creating a version needs `write`");
        }
        versions.lockContentOf(access.skillId());
        Optional<VersionRepository.VersionRow> existing =
                versions.findBySkillAndDigest(access.skillId(), manifest.digest(), false);
        if (existing.isPresent()) {
            throw contentConflict(access, existing.get());
        }
        Optional<String> inserted = versions.insertIfAbsent(access.skillId(), skill, draftName,
                note, manifest.digest(), manifest.fileCount(), manifest.totalBytes(), source,
                submittedBy, at);
        if (inserted.isEmpty()) {
            // The unique constraint is the second line of defence: another transaction inserted the
            // same digest between the check above and this insert. Answer with that row, not a 500.
            VersionRepository.VersionRow raced = versions
                    .findBySkillAndDigest(access.skillId(), manifest.digest(), false)
                    .orElseThrow(() -> new IllegalStateException(
                            "content " + manifest.digest() + " vanished mid-create"));
            throw contentConflict(access, raced);
        }
        versions.insertFiles(inserted.get(), manifest);
        metadata.recordSubmission(access.skillId(), draftProjectionOf(access.skillId()));
        VersionRepository.VersionRow row = versions.findByRowId(access.skillId(), inserted.get())
                .orElseThrow(() -> new IllegalStateException(
                        "version " + inserted.get() + " vanished after create"));
        return summary(access, row);
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
            case VersionPin.RowId(String id) -> versions.findByRowId(skillId, id);
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
            case VersionPin.RowId(String id) -> "row " + id;
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
    public Optional<PromotionOutcome> publishVersion(Access access, VersionPin pin,
            String releaseName) {
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

        if (VersionState.REVOKED.equals(version.state())) {
            // A revoked version has exactly one forward action: restore. Letting publish fall
            // through would move the pointer onto a row that is not `published` — the state the
            // consumption plane filters on — so every bare address of this skill would then be a
            // 500. The one-directional rule is TD §版本状态机's.
            throw new VersionStateException(describe(pin) + " of '" + name
                    + "' is revoked; restore it before publishing it again",
                    com.skillmasterai.common.ErrorCode.INVALID_VERSION_STATE);
        }

        if (VersionState.PUBLISHED.equals(version.state())) {
            // A number is assigned once. Asking an already-numbered version to take another one is
            // a different request from publishing it, and it is refused rather than ignored.
            if (releaseName != null && !releaseName.equals(version.version())) {
                throw new VersionStateException(describe(pin) + " of '" + name
                        + "' already has version " + version.version()
                        + "; a version number is assigned once",
                        com.skillmasterai.common.ErrorCode.VERSION_NAME_IS_ASSIGNED);
            }
            if (version.id().equals(access.currentVersionId())) {
                // Already what consumers get. Nothing is written and there is nothing to audit: this
                // is the same state, reached twice. Rolling back onto an *older* published version
                // is not this case and does write.
                return Optional.of(new PromotionOutcome(skillId, version.version(), version.draftName(), version.digest(),
                        firstLiveAt(version, at), false));
            }
            // **Rollback.** Both rows record the move: the target becomes current, and the version
            // it displaces stops being current. Only `state_at` moves — `first_published_at` is a
            // first time and never a latest one. Both rows or neither: one match means a concurrent
            // revoke changed one of them between the read and this write.
            // A pointer that names nothing is the v6 upgrade case: a gateway row published before
            // this version, with no current pointer to displace. There is one row to record, so the
            // two-row move does not apply.
            if (access.currentVersionId() != null
                    && versions.markRolledBack(version.id(), access.currentVersionId(), at) != 2) {
                throw new VersionStateException(describe(pin) + " of '" + name
                        + "' changed state while being rolled back; nothing was moved");
            }
            boolean moved = metadata.recordPublish(skillId, projectionOf(version, at),
                    draftProjectionOf(skillId), at);
            return Optional.of(new PromotionOutcome(skillId, version.version(), version.draftName(), version.digest(),
                    firstLiveAt(version, at), moved));
        }

        // From here the only state left is draft. Its number comes from the release the publisher
        // chose; a legacy draft that already declared one keeps it and must not be renamed here.
        String assigned = version.version();
        if (assigned == null) {
            if (releaseName == null || releaseName.isBlank()) {
                throw new VersionStateException(describe(pin) + " of '" + name
                        + "' has no version number yet; publishing a draft needs a release name",
                        com.skillmasterai.common.ErrorCode.RELEASE_NAME_REQUIRED);
            }
            if (!com.skillmasterai.common.SemVer.isValid(releaseName)) {
                throw new VersionStateException("'" + releaseName + "' is not a SemVer 2.0.0"
                        + " version number",
                        com.skillmasterai.common.ErrorCode.INVALID_RELEASE_NAME);
            }
            // A concurrent publish of this same row may already have assigned this very number to
            // it. That is the same intent reached twice, not a collision — only another row holding
            // the number is the collision.
            String targetId = version.id();
            boolean taken = versions.findBySkillAndVersion(skillId, releaseName, false)
                    .filter(other -> !other.id().equals(targetId))
                    .isPresent();
            if (taken) {
                throw new VersionNameTakenException("'" + name + "' already has a version named "
                        + releaseName + "; version numbers are never reused");
            }
        } else if (releaseName != null && !releaseName.equals(assigned)) {
            throw new VersionStateException(describe(pin) + " of '" + name
                    + "' already has version " + assigned
                    + "; a version number is assigned once",
                    com.skillmasterai.common.ErrorCode.VERSION_NAME_IS_ASSIGNED);
        }

        if (VersionState.DRAFT.equals(version.state())) {
            if (versions.markPublished(version.id(), assigned == null ? releaseName : null,
                    at) == 0) {
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
                if (VersionState.REVOKED.equals(now.state())) {
                    // Same refusal as the up-front guard above, reached through the race: a
                    // concurrent revoke cannot happen to a draft, but a concurrent restore-then-
                    // revoke can, and the pointer must not move onto a row that is not published.
                    throw new VersionStateException(describe(pin) + " of '" + name
                            + "' was revoked while being published",
                            com.skillmasterai.common.ErrorCode.INVALID_VERSION_STATE);
                }
                // Two requests published this row with different names: the winner's assignment is
                // the one that happened, so the loser is told its name was not taken rather than
                // silently handed a response naming a version it did not choose.
                if (releaseName != null && now.version() != null
                        && !releaseName.equals(now.version())) {
                    throw new VersionStateException(describe(pin) + " of '" + name
                            + "' was published as " + now.version() + " by another request; a "
                            + "version number is assigned once",
                            com.skillmasterai.common.ErrorCode.VERSION_NAME_IS_ASSIGNED);
                }
                // Published by the request that won: this call is the other half of the same intent
                // and finishes it, exactly as the rollback path below does. Its own time is reported,
                // not this request's — the version went live when the winner wrote it.
                version = now;
            }
        }

        // Read back after the write: the number this version now has is the one the update
        // assigned, and `first_published_at` is the update's answer rather than this call's clock.
        VersionRepository.VersionRow published = versionFor(skillId, pin, false)
                .orElseThrow(() -> new IllegalStateException(describe(pin) + " of '"
                        + name + "' vanished while being published"));

        // `changed` is what the statement did, not what this method expected it to do. The pointer
        // comparison above is against a value read earlier in this transaction, so it is a guess;
        // the update is the fact. When the pointer already names this version — the loser of a race
        // to publish it, most often — nothing is written and the caller must not audit a change
        // that did not happen.
        boolean moved = metadata.recordPublish(skillId, projectionOf(published, at),
                draftProjectionOf(skillId), at);
        return Optional.of(new PromotionOutcome(skillId, published.version(), published.draftName(), published.digest(),
                firstLiveAt(published, at), moved));
    }

    private static VersionProjection projectionOf(VersionRepository.VersionRow version, String at) {
        return new VersionProjection(version.id(), version.version(), version.digest(),
                version.submittedAt(), version.title(), version.description(),
                version.frontmatter());
    }

    /**
     * When this version first went live. The stored column wins whenever it is set; {@code at} is
     * only the answer for a row this call has just published.
     */
    private static String firstLiveAt(VersionRepository.VersionRow version, String at) {
        if (version.firstPublishedAt() != null) {
            return version.firstPublishedAt();
        }
        return version.stateAt() != null ? version.stateAt() : at;
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
        boolean permitted = access.atLeast(GrantRole.MAINTAIN)
                || (access.atLeast(GrantRole.WRITE) && mine);
        // Invisible and unable to act is the same answer as "no such version" — otherwise a `browse`
        // caller holding a draft id learns it exists from a 403 whose message says who wrote it.
        // Checked before the 403 so the visible-but-not-yours case still gets its truthful refusal.
        if (!visibleTo(access, found.get().state()) && !permitted) {
            return Optional.empty();
        }
        if (!permitted) {
            throw new NotPermittedException("'" + name + "' was submitted by somebody else; "
                    + "the `write` rung discards your own drafts, and discarding other people's "
                    + "needs `maintain`");
        }
        if (VersionState.DISCARDED.equals(found.get().state())) {
            // Already the state this call asks for. A second discard is the same intent reached
            // twice, and answering 200 with the row is what lets a CLI retry safely (T37 ④).
            return Optional.of(skillId);
        }
        if (!VersionState.DRAFT.equals(found.get().state())) {
            throw new VersionStateException(describe(pin) + " of '" + name + "' is "
                    + found.get().state() + "; only a draft can be discarded",
                    com.skillmasterai.common.ErrorCode.INVALID_VERSION_STATE);
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
     * Replaces a draft's name (ADR 0055).
     *
     * <p>Who may: {@code write} on a draft they submitted, or {@code maintain} and above on any
     * draft — the same rule as discard, because it is the same kind of act, a write to somebody
     * else's row. The state guard is separate: a published version's name is frozen.
     *
     * @return the row as it now reads, or empty when the pin is not visible to this caller
     */
    public Optional<String> updateDraftName(Access access, VersionPin pin, String name) {
        return updateDraft(access, pin, version -> versions.updateDraftName(version.id(), name),
                com.skillmasterai.common.ErrorCode.DRAFT_NAME_IS_FROZEN, "name");
    }

    /** The same, for the one-line note. */
    public Optional<String> updateDraftNote(Access access, VersionPin pin, String note) {
        return updateDraft(access, pin, version -> versions.updateDraftNote(version.id(), note),
                com.skillmasterai.common.ErrorCode.NOTE_IS_FROZEN, "note");
    }

    private Optional<String> updateDraft(Access access, VersionPin pin,
            java.util.function.ToIntFunction<VersionRepository.VersionRow> write,
            com.skillmasterai.common.ErrorCode frozen, String what) {
        Optional<VersionRepository.VersionRow> found = versionFor(access.skillId(), pin, false)
                .filter(version -> visibleTo(access, version.state()));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        VersionRepository.VersionRow version = found.get();
        boolean mine = access.callerId().equals(version.submittedBy());
        if (!access.atLeast(GrantRole.MAINTAIN)
                && !(access.atLeast(GrantRole.WRITE) && mine)) {
            throw new NotPermittedException("'" + access.name() + "' was submitted by somebody"
                    + " else; changing its " + what + " needs its submitter or the `maintain` rung");
        }
        if (!VersionState.DRAFT.equals(version.state())) {
            throw new VersionStateException(describe(pin) + " of '" + access.name()
                    + "' is " + version.state() + "; its " + what + " froze when it was published",
                    frozen);
        }
        if (write.applyAsInt(version) == 0) {
            throw new VersionStateException(describe(pin) + " of '" + access.name()
                    + "' changed state while being edited; nothing was written",
                    frozen);
        }
        return Optional.of(access.skillId());
    }

    private static boolean visibleTo(Access access, String state) {
        return visibleStates(access).contains(state);
    }

    /**
     * Takes a published, non-current version down (ADR 0058).
     *
     * <p>The current version cannot be revoked: callers holding the bare address would lose what
     * they are reading, and the pointer would name nothing. Rollback first, then revoke.
     *
     * @param reason optional, 0-200 code points (ADR 0059)
     * @return the row as it now reads, or empty when the pin names nothing
     */
    public Optional<String> revokeVersion(Access access, VersionPin pin, String reason) {
        requireMaintain(access, "revoke a version");
        Optional<VersionRepository.VersionRow> found = versionFor(access.skillId(), pin, false);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        VersionRepository.VersionRow version = found.get();
        if (!VersionState.PUBLISHED.equals(version.state())) {
            throw new VersionStateException(describe(pin) + " of '" + access.name()
                    + "' is " + version.state() + "; only a published version can be taken down",
                    com.skillmasterai.common.ErrorCode.INVALID_VERSION_STATE);
        }
        if (version.id().equals(access.currentVersionId())) {
            throw new VersionStateException("the current version of '" + access.name()
                    + "' cannot be taken down; roll back to another version first",
                    com.skillmasterai.common.ErrorCode.INVALID_VERSION_STATE);
        }
        if (versions.markRevoked(version.id(), Timestamps.now(), reason) == 0) {
            throw new VersionStateException(describe(pin) + " of '" + access.name()
                    + "' changed state while being taken down; nothing was written",
                    com.skillmasterai.common.ErrorCode.INVALID_VERSION_STATE);
        }
        // **The pointer is checked again after the write.** The guard above read `Access`, which was
        // taken before this transaction; a rollback committing in between would make "not current"
        // stale, and the update is guarded on `state = 'published'` alone, so it would succeed. That
        // leaves the pointer naming a revoked row — the consumption plane's `findCurrent` filters on
        // `published`, so every bare address of this skill would then be a 500. Throwing here rolls
        // the revoke back, which is the only outcome that keeps invariant 1.
        if (version.id().equals(metadata.currentVersionId(access.skillId()).orElse(null))) {
            throw new VersionStateException(describe(pin) + " of '" + access.name()
                    + "' became the current version while being taken down; roll back to another "
                    + "version first",
                    com.skillmasterai.common.ErrorCode.INVALID_VERSION_STATE);
        }
        return Optional.of(access.skillId());
    }

    /** Puts a revoked version back among the readable ones. It stays history; the pointer does not move. */
    public Optional<String> restoreVersion(Access access, VersionPin pin) {
        requireMaintain(access, "restore a version");
        Optional<VersionRepository.VersionRow> found = versionFor(access.skillId(), pin, false);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (!VersionState.REVOKED.equals(found.get().state())) {
            throw new VersionStateException(describe(pin) + " of '" + access.name()
                    + "' is " + found.get().state() + "; only a taken-down version can be restored",
                    com.skillmasterai.common.ErrorCode.INVALID_VERSION_STATE);
        }
        if (versions.markRestored(found.get().id(), Timestamps.now()) == 0) {
            throw new VersionStateException(describe(pin) + " of '" + access.name()
                    + "' changed state while being restored; nothing was written",
                    com.skillmasterai.common.ErrorCode.INVALID_VERSION_STATE);
        }
        return Optional.of(access.skillId());
    }

    private static void requireMaintain(Access access, String action) {
        if (!access.atLeast(GrantRole.MAINTAIN)) {
            throw new NotPermittedException("to " + action + " on '" + access.name()
                    + "' needs the `maintain` rung");
        }
    }

    /**
     * The versions of a skill this rung may read, in submission order — the author list.
     *
     * <p>Returned whole; the caller (the use case) applies the status filter, the search and the
     * page, because the search matches a submitter's handle and that is M1's word, not M7's.
     */
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
                        row.id().equals(access.currentVersionId())));
    }

    public List<VersionSummary> authorVersions(Access access) {
        return versions.versionsVisible(access.skillId(), access.currentVersionId(),
                visibleStates(access));
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
                access.currentVersionId(), visibleStates(access));

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
                access.currentVersionId(), visibleStates(access));
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
            Set<String> visible = visibleStates(access);
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
        return versions.findVisible(access.skillId(), pin, visibleStates(access));
    }

    /**
     * The stored states a rung may read on the author plane (ADR 0058).
     *
     * <p>Published is everyone who can see the skill; drafts are {@code write} and up; revoked and
     * discarded rows are {@code maintain} and up. The set moves as one fact so a list and a pinned
     * read never disagree about whether a row exists.
     */
    private static Set<String> visibleStates(Access access) {
        if (access.atLeast(GrantRole.MAINTAIN)) {
            return Set.of(VersionState.DRAFT, VersionState.PUBLISHED, VersionState.REVOKED,
                    VersionState.DISCARDED);
        }
        if (access.atLeast(GrantRole.WRITE)) {
            return Set.of(VersionState.DRAFT, VersionState.PUBLISHED);
        }
        return Set.of(VersionState.PUBLISHED);
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
            throw new VersionRevokedException("'" + describe(pin) + "' of '" + access.name()
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

    private DraftProjection draftProjectionOf(String skillId) {
        return versions.draftProjectionOf(skillId).orElse(DraftProjection.empty());
    }
}
