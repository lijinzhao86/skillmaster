package com.skillmasterai.modules.version;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.GrantRole;
import com.skillmasterai.modules.skill.NotPermittedException;
import com.skillmasterai.modules.version.internal.VersionRepository;
import java.util.List;
import java.util.Optional;

/** Internal collaborator extracted from {@link SkillVersionService}. */
final class VersionLifecycleService {

    private final VersionRepository versions;
    private final VersionProjectionSink metadata;
    private final VersionRows rows;

    VersionLifecycleService(VersionRepository versions, VersionProjectionSink metadata, VersionRows rows) {
        this.versions = versions;
        this.metadata = metadata;
        this.rows = rows;
    }

    /**
     * The drafts of several skills at once, for the publish queue.
     *
     * <p>No access check here: the ids came from M12's listing, which filtered by the rung the caller
     * holds. Re-asking would be a second answer to a question already answered, and the queue is a
     * read that changes nothing.
     */
    public List<PublishDraft> draftsOf(java.util.Collection<String> skillIds) {
        return versions.draftsOf(skillIds).stream()
                .map(draft -> new PublishDraft(draft.skillId(), draft.id(), draft.draftName(),
                        draft.releaseType(), draft.submittedAt()))
                .toList();
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

        Optional<VersionRepository.VersionRow> found = rows.versionFor(skillId, pin, false);
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
        if (!rows.visibleTo(access, found.get().state()) && !permitted) {
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
            throw new VersionStateException(rows.describe(pin) + " of '" + name + "' is "
                    + found.get().state() + "; only a draft can be discarded",
                    com.skillmasterai.common.ErrorCode.INVALID_VERSION_STATE);
        }

        if (versions.markDiscarded(found.get().id(), at) == 0) {
            // Same misreading this method's sibling had, and the same fix. The guard is
            // `state = 'draft'`, and zero means the row stopped being a draft between the read above
            // and this update — which a concurrent *discard of this same version* does just as
            // readily as a concurrent publish. Reading only the zero told a double click on 丢弃 that
            // its version had just been published, which is both false and alarming.
            VersionRepository.VersionRow now = rows.versionFor(skillId, pin, false)
                    .orElseThrow(() -> new IllegalStateException(rows.describe(pin) + " of '"
                            + name + "' vanished while being discarded"));
            if (!VersionState.DISCARDED.equals(now.state())) {
                throw new VersionStateException(rows.describe(pin) + " of '" + name
                        + "' was published while being discarded");
            }
            // Discarded by the request that won. The state this call asked for is the state there is,
            // so it succeeds — discarding is idempotent in the same way publishing is.
        }

        metadata.recordDiscard(skillId, rows.draftProjectionOf(skillId));
        return Optional.of(skillId);
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
        Optional<VersionRepository.VersionRow> found = rows.versionFor(access.skillId(), pin, false);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        VersionRepository.VersionRow version = found.get();
        if (!VersionState.PUBLISHED.equals(version.state())) {
            throw new VersionStateException(rows.describe(pin) + " of '" + access.name()
                    + "' is " + version.state() + "; only a published version can be taken down",
                    com.skillmasterai.common.ErrorCode.INVALID_VERSION_STATE);
        }
        if (version.id().equals(access.currentVersionId())) {
            throw new VersionStateException("the current version of '" + access.name()
                    + "' cannot be taken down; roll back to another version first",
                    com.skillmasterai.common.ErrorCode.INVALID_VERSION_STATE);
        }
        if (versions.markRevoked(version.id(), Timestamps.now(), reason) == 0) {
            throw new VersionStateException(rows.describe(pin) + " of '" + access.name()
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
            throw new VersionStateException(rows.describe(pin) + " of '" + access.name()
                    + "' became the current version while being taken down; roll back to another "
                    + "version first",
                    com.skillmasterai.common.ErrorCode.INVALID_VERSION_STATE);
        }
        return Optional.of(access.skillId());
    }

    /** Puts a revoked version back among the readable ones. It stays history; the pointer does not move. */
    public Optional<String> restoreVersion(Access access, VersionPin pin) {
        requireMaintain(access, "restore a version");
        Optional<VersionRepository.VersionRow> found = rows.versionFor(access.skillId(), pin, false);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (!VersionState.REVOKED.equals(found.get().state())) {
            throw new VersionStateException(rows.describe(pin) + " of '" + access.name()
                    + "' is " + found.get().state() + "; only a taken-down version can be restored",
                    com.skillmasterai.common.ErrorCode.INVALID_VERSION_STATE);
        }
        if (versions.markRestored(found.get().id(), Timestamps.now()) == 0) {
            throw new VersionStateException(rows.describe(pin) + " of '" + access.name()
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
}
