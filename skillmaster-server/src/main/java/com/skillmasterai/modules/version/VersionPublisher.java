package com.skillmasterai.modules.version;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.GrantRole;
import com.skillmasterai.modules.skill.NotPermittedException;
import com.skillmasterai.modules.version.internal.VersionRepository;
import java.util.Optional;

/** Internal collaborator extracted from {@link SkillVersionService}. */
final class VersionPublisher {

    private final VersionRepository versions;
    private final VersionProjectionSink metadata;
    private final VersionRows rows;

    VersionPublisher(VersionRepository versions, VersionProjectionSink metadata, VersionRows rows) {
        this.versions = versions;
        this.metadata = metadata;
        this.rows = rows;
    }

    /**
     * The number a draft's declared release type produces — ADR 0091, and the only place it happens.
     *
     * <p>Reached only when the publisher named no number and the draft carries none of its own.
     *
     * <p><strong>"Never published" is answered before the type is even read.</strong> The first
     * version of a skill is {@code 1.0.0} whether or not the draft declared a type and whatever it
     * declared (ADR 0091 §决定 4) — so a skill nobody has published needs nothing from the
     * publisher, and asking for a type there would refuse a publish that has an answer. The order
     * used to be the other way round, and it turned the queue's most ordinary row — a first draft
     * with no type, which the page rightly shows as {@code 1.0.0} — into a 400.
     *
     * @return the number, or null when a published skill's draft declares no type — the caller
     *         answers that with {@code release_name_required}, which is the older code for "no
     *         basis at all"
     * @throws VersionStateException when the draft declares a type but the live version cannot be
     *         bumped: it has no number, it is not a valid version at all, or it is a prerelease.
     *         All three get {@code release_type_needs_explicit_name}, because the publisher has to
     *         do the same thing in each of them — name a number (ADR 0091 §决定 5)
     */
    private String numberFromDeclaredType(VersionRepository.VersionRow draft, Access access) {
        if (access.currentVersionId() == null) {
            // Never published: the first version is 1.0.0, and the type is not consulted.
            return "1.0.0";
        }
        String declared = draft.releaseType();
        if (declared == null) {
            return null;
        }
        ReleaseType type = ReleaseType.from(declared).orElseThrow(() -> new VersionStateException(
                "release type '" + declared + "' on '" + access.name()
                        + "' is not one of major / minor / patch",
                com.skillmasterai.common.ErrorCode.INVALID_REQUEST));

        String base = versions.findCurrent(access.currentVersionId())
                .map(VersionRepository.VersionRow::version)
                .orElse(null);
        if (base == null || base.isBlank()) {
            throw new VersionStateException("'" + access.name() + "' is live at a version with no "
                    + "number; publishing with a release type needs an explicit version number",
                    com.skillmasterai.common.ErrorCode.RELEASE_TYPE_NEEDS_EXPLICIT_NAME);
        }
        return type.applyTo(base).orElseThrow(() -> new VersionStateException(
                "'" + access.name() + "' is live at " + base + ", which is a prerelease; "
                        + "publishing with a release type needs an explicit version number",
                com.skillmasterai.common.ErrorCode.RELEASE_TYPE_NEEDS_EXPLICIT_NAME));
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

        Optional<VersionRepository.VersionRow> found = rows.versionFor(skillId, pin, false);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        VersionRepository.VersionRow version = found.get();

        if (VersionState.DISCARDED.equals(version.state())) {
            throw new VersionStateException(rows.describe(pin) + " of '" + name
                    + "' was discarded and cannot be published");
        }

        if (VersionState.REVOKED.equals(version.state())) {
            // A revoked version has exactly one forward action: restore. Letting publish fall
            // through would move the pointer onto a row that is not `published` — the state the
            // consumption plane filters on — so every bare address of this skill would then be a
            // 500. The one-directional rule is TD §版本状态机's.
            throw new VersionStateException(rows.describe(pin) + " of '" + name
                    + "' is revoked; restore it before publishing it again",
                    com.skillmasterai.common.ErrorCode.INVALID_VERSION_STATE);
        }

        if (VersionState.PUBLISHED.equals(version.state())) {
            // A number is assigned once. Asking an already-numbered version to take another one is
            // a different request from publishing it, and it is refused rather than ignored.
            if (releaseName != null && !releaseName.equals(version.version())) {
                throw new VersionStateException(rows.describe(pin) + " of '" + name
                        + "' already has version " + version.version()
                        + "; a version number is assigned once",
                        com.skillmasterai.common.ErrorCode.VERSION_NAME_IS_ASSIGNED);
            }
            if (version.id().equals(access.currentVersionId())) {
                // Already what consumers get. Nothing is written and there is nothing to audit: this
                // is the same state, reached twice. Rolling back onto an *older* published version
                // is not this case and does write.
                return Optional.of(new PromotionOutcome(skillId, version.version(), version.draftName(), version.digest(),
                        VersionRows.firstLiveAt(version, at), false));
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
                throw new VersionStateException(rows.describe(pin) + " of '" + name
                        + "' changed state while being rolled back; nothing was moved");
            }
            boolean moved = metadata.recordPublish(skillId, VersionRows.projectionOf(version, at),
                    rows.draftProjectionOf(skillId), at);
            return Optional.of(new PromotionOutcome(skillId, version.version(), version.draftName(), version.digest(),
                    VersionRows.firstLiveAt(version, at), moved));
        }

        // From here the only state left is draft. Its number comes from the release the publisher
        // named, or — when they named none — from the type this draft declares and the version that
        // is live right now (ADR 0091). A legacy draft that already declared one keeps it and must
        // not be renamed here.
        String assigned = version.version();
        String resolved = releaseName;
        if (assigned == null && (resolved == null || resolved.isBlank())) {
            resolved = numberFromDeclaredType(version, access);
        }
        if (assigned == null) {
            if (resolved == null || resolved.isBlank()) {
                throw new VersionStateException(rows.describe(pin) + " of '" + name
                        + "' has no version number yet; publishing a draft needs a release name",
                        com.skillmasterai.common.ErrorCode.RELEASE_NAME_REQUIRED);
            }
            if (!com.skillmasterai.common.SemVer.isValid(resolved)) {
                throw new VersionStateException("'" + resolved + "' is not a SemVer 2.0.0"
                        + " version number",
                        com.skillmasterai.common.ErrorCode.INVALID_RELEASE_NAME);
            }
            // A concurrent publish of this same row may already have assigned this very number to
            // it. That is the same intent reached twice, not a collision — only another row holding
            // the number is the collision.
            String targetId = version.id();
            boolean taken = versions.findBySkillAndVersion(skillId, resolved, false)
                    .filter(other -> !other.id().equals(targetId))
                    .isPresent();
            if (taken) {
                throw new VersionNameTakenException("'" + name + "' already has a version named "
                        + resolved + "; version numbers are never reused");
            }
        } else if (releaseName != null && !releaseName.equals(assigned)) {
            throw new VersionStateException(rows.describe(pin) + " of '" + name
                    + "' already has version " + assigned
                    + "; a version number is assigned once",
                    com.skillmasterai.common.ErrorCode.VERSION_NAME_IS_ASSIGNED);
        }

        if (VersionState.DRAFT.equals(version.state())) {
            if (versions.markPublished(version.id(), assigned == null ? resolved : null,
                    at) == 0) {
                // Zero means the row stopped being a draft between the read above and this update,
                // and **two different transitions** do that: a concurrent discard, and a concurrent
                // publish of this same version — a double click on 上线, or a retry. The guard is
                // `state = 'draft'` and cannot tell them apart, so the row is read again and the
                // answer is decided on what it now says rather than on what the zero might have meant.
                VersionRepository.VersionRow now = rows.versionFor(skillId, pin, false)
                        .orElseThrow(() -> new IllegalStateException(rows.describe(pin) + " of '"
                                + name + "' vanished while being published"));
                if (VersionState.DISCARDED.equals(now.state())) {
                    // Refusing beats moving the pointer onto something the author has just thrown away.
                    throw new VersionStateException(rows.describe(pin) + " of '" + name
                            + "' was discarded while being published");
                }
                if (VersionState.REVOKED.equals(now.state())) {
                    // Same refusal as the up-front guard above, reached through the race: a
                    // concurrent revoke cannot happen to a draft, but a concurrent restore-then-
                    // revoke can, and the pointer must not move onto a row that is not published.
                    throw new VersionStateException(rows.describe(pin) + " of '" + name
                            + "' was revoked while being published",
                            com.skillmasterai.common.ErrorCode.INVALID_VERSION_STATE);
                }
                // Two requests published this row with different names: the winner's assignment is
                // the one that happened, so the loser is told its name was not taken rather than
                // silently handed a response naming a version it did not choose.
                if (releaseName != null && now.version() != null
                        && !releaseName.equals(now.version())) {
                    throw new VersionStateException(rows.describe(pin) + " of '" + name
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
        VersionRepository.VersionRow published = rows.versionFor(skillId, pin, false)
                .orElseThrow(() -> new IllegalStateException(rows.describe(pin) + " of '"
                        + name + "' vanished while being published"));

        // `changed` is what the statement did, not what this method expected it to do. The pointer
        // comparison above is against a value read earlier in this transaction, so it is a guess;
        // the update is the fact. When the pointer already names this version — the loser of a race
        // to publish it, most often — nothing is written and the caller must not audit a change
        // that did not happen.
        boolean moved = metadata.recordPublish(skillId, VersionRows.projectionOf(published, at),
                rows.draftProjectionOf(skillId), at);
        return Optional.of(new PromotionOutcome(skillId, published.version(), published.draftName(), published.digest(),
                VersionRows.firstLiveAt(published, at), moved));
    }
}
