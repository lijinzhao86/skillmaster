package com.skillmasterai.modules.version;

import com.skillmasterai.common.ConflictException;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.GrantRole;
import com.skillmasterai.modules.skill.NotPermittedException;
import com.skillmasterai.modules.skill.SkillMetadata;
import com.skillmasterai.modules.version.internal.VersionRepository;
import java.util.Optional;

/** Internal collaborator extracted from {@link SkillVersionService}. */
final class VersionDraftEditor {

    private final VersionRepository versions;
    private final VersionRows rows;

    VersionDraftEditor(VersionRepository versions, VersionRows rows) {
        this.versions = versions;
        this.rows = rows;
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
        if (!rows.visibleTo(access, row.state())) {
            // A `write` caller holding a discarded row's id learns nothing — the same 404 as an id
            // that names nothing, which is what "not visible to this rung" means everywhere else.
            return Optional.empty();
        }
        if (!VersionState.DRAFT.equals(row.state())) {
            throw rows.notDraft(access, row, "this row is ");
        }
        boolean mine = access.callerId().equals(row.submittedBy());
        if (!access.atLeast(GrantRole.MAINTAIN) && !mine) {
            throw new NotPermittedException("'" + access.name() + "' was submitted by somebody "
                    + "else; the `write` rung changes your own drafts, and changing other people's "
                    + "needs `maintain`");
        }
        return Optional.of(rows.summary(access, row));
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
            throw rows.notDraft(access, row, "this row is now ");
        }
        return rows.summary(access, row);
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
     * 200, and writes nothing), then write. Drafts may share content with any other row (ADR 0135),
     * so an unchanged digest is the only no-op; the same bytes written again still write nothing.
     *
     * @param metadata the new title/description/frontmatter when the write touched the root
     *                 {@code SKILL.md}, or null
     * @return the row as it now reads; the locked row unchanged on a no-op
     */
    public VersionSummary commitContent(Access access, VersionSummary locked, Manifest next,
            SkillMetadata metadata) {
        String digest = next.digest();
        if (digest.equals(locked.digest())) {
            return locked;
        }
        // **One critical section per skill.** Two writes to the same draft must not interleave:
        // the second would compute its manifest from a row the first already moved. Drafts may
        // share content with any other row (ADR 0135), so there is no content check here.
        versions.lockContentOf(access.skillId());
        if (versions.updateContent(locked.id(), digest, next.fileCount(), next.totalBytes(),
                metadata) == 0) {
            throw new ConflictException(
                    com.skillmasterai.common.ErrorCode.VERSION_IS_NOT_DRAFT,
                    "write only applies to a draft; this row changed state while being written");
        }
        // The skill card's "waiting" count is this version set (V21), and the set has changed.
        rows.refreshDraftProjection(access.skillId());
        return versions.findByRowId(access.skillId(), locked.id())
                .map(row -> rows.summary(access, row))
                .orElseThrow(() -> new IllegalStateException(
                        "version " + locked.id() + " vanished after a write"));
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

    /**
     * Replaces (or clears) the release type a draft declares — ADR 0091.
     *
     * <p><strong>Not the same rung as the name and the note.</strong> Those two are the submitter's
     * or {@code maintain}'s, because they change a label somebody else wrote; the type changes
     * nothing that is visible, it is an input to the next publish, so {@code write} is enough
     * (ADR 0091 §后果 3).
     *
     * @param releaseType the canonical spelling, or null to clear it
     */
    public Optional<String> updateDraftReleaseType(Access access, VersionPin pin,
            String releaseType) {
        return updateDraft(access, pin,
                version -> versions.updateReleaseType(version.id(), releaseType),
                com.skillmasterai.common.ErrorCode.RELEASE_TYPE_IS_FROZEN, "release type",
                WhoMayChange.ANY_WRITE);
    }

    /**
     * Who may change a draft's metadata. The two rungs are a real distinction rather than a flag:
     * a name and a note are labels somebody wrote, and the type is an input to the next publish.
     */
    private enum WhoMayChange {
        /** The name and the note: the submitter, or {@code maintain}. */
        SUBMITTER_OR_MAINTAIN,
        /** The release type: anyone at {@code write} (ADR 0091 §后果 3). */
        ANY_WRITE
    }

    private Optional<String> updateDraft(Access access, VersionPin pin,
            java.util.function.ToIntFunction<VersionRepository.VersionRow> write,
            com.skillmasterai.common.ErrorCode frozen, String what) {
        return updateDraft(access, pin, write, frozen, what, WhoMayChange.SUBMITTER_OR_MAINTAIN);
    }

    private Optional<String> updateDraft(Access access, VersionPin pin,
            java.util.function.ToIntFunction<VersionRepository.VersionRow> write,
            com.skillmasterai.common.ErrorCode frozen, String what, WhoMayChange who) {
        Optional<VersionRepository.VersionRow> found = rows.versionFor(access.skillId(), pin, false)
                .filter(version -> rows.visibleTo(access, version.state()));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        VersionRepository.VersionRow version = found.get();
        boolean mine = access.callerId().equals(version.submittedBy());
        if (who == WhoMayChange.ANY_WRITE && !access.atLeast(GrantRole.WRITE)) {
            throw new NotPermittedException("changing the release type of '" + access.name()
                    + "' needs the `write` rung");
        }
        if (who == WhoMayChange.SUBMITTER_OR_MAINTAIN && !access.atLeast(GrantRole.MAINTAIN)
                && !(access.atLeast(GrantRole.WRITE) && mine)) {
            throw new NotPermittedException("'" + access.name() + "' was submitted by somebody"
                    + " else; changing its " + what + " needs its submitter or the `maintain` rung");
        }
        if (!VersionState.DRAFT.equals(version.state())) {
            throw new VersionStateException(rows.describe(pin) + " of '" + access.name()
                    + "' is " + version.state() + "; its " + what + " froze when it was published",
                    frozen);
        }
        if (write.applyAsInt(version) == 0) {
            throw new VersionStateException(rows.describe(pin) + " of '" + access.name()
                    + "' changed state while being edited; nothing was written",
                    frozen);
        }
        return Optional.of(access.skillId());
    }
}
