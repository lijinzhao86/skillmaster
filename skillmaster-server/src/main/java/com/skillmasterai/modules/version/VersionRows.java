package com.skillmasterai.modules.version;

import com.skillmasterai.common.ConflictException;
import com.skillmasterai.modules.skill.Access;
import com.skillmasterai.modules.skill.DraftProjection;
import com.skillmasterai.modules.skill.GrantRole;
import com.skillmasterai.modules.skill.VersionProjection;
import com.skillmasterai.modules.version.internal.VersionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Internal collaborator extracted from {@link SkillVersionService}. */
final class VersionRows {

    private final VersionRepository versions;
    private final VersionProjectionSink metadata;

    VersionRows(VersionRepository versions, VersionProjectionSink metadata) {
        this.versions = versions;
        this.metadata = metadata;
    }

    /**
     * The refusal for a row that is not a draft any more.
     *
     * <p>The state is always named — it is why the write cannot happen. The pin is named only when
     * the caller can actually open that state: published is visible to every `write`+ caller, while
     * a revoked or discarded row is visible only to `maintain`+. Handing a `write` caller an id the
     * next request refuses would be the same information-boundary mistake the gate order avoids.
     */
    ConflictException notDraft(Access access, VersionRepository.VersionRow row,
            String wording) {
        List<com.skillmasterai.common.ApiError.Detail> details = new ArrayList<>();
        details.add(new com.skillmasterai.common.ApiError.Detail("state", row.state()));
        if (VersionState.PUBLISHED.equals(row.state()) || access.atLeast(GrantRole.MAINTAIN)) {
            details.add(new com.skillmasterai.common.ApiError.Detail("pin", pinOf(row)));
        }
        return new ConflictException(com.skillmasterai.common.ErrorCode.VERSION_IS_NOT_DRAFT,
                "write only applies to a draft; " + wording + row.state(), details);
    }

    /**
     * One answer for "this content is already another version's", from either path.
     *
     * <p>The pin is only named when the caller may see that state: a `write` caller cannot read a
     * discarded or delisted row, and naming its id in a 409 would hand them an address the next
     * request refuses (v7 门序 ②). The state is always named, because it is why the content is not
     * available rather than a fact about a row they can open.
     */
    ConflictException contentConflict(Access access, VersionRepository.VersionRow existing) {
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

    void refreshDraftProjection(String skillId) {
        metadata.recordSubmission(skillId, draftProjectionOf(skillId));
    }

    /** A row as the author list reads it — the shape a write echoes back. */
    VersionSummary summary(Access access, VersionRepository.VersionRow row) {
        return new VersionSummary(row.id(), row.version(), row.draftName(), row.note(),
                row.digest(), row.title(), row.fileCount(), row.totalBytes(), row.submittedAt(),
                row.submittedBy(), row.state(), row.stateAt(), row.firstPublishedAt(),
                row.revokedReason(), row.id().equals(access.currentVersionId()),
                row.releaseType());
    }

    String pinOf(VersionRepository.VersionRow row) {
        if (row.version() != null) {
            return row.version();
        }
        if (VersionState.DRAFT.equals(row.state()) || VersionState.DISCARDED.equals(row.state())) {
            return row.id();
        }
        return "sha256:" + row.digest();
    }

    /**
     * The version a pin names, or empty when it names nothing.
     *
     * <p>For the two operations that act on <em>one</em> version, so {@link VersionPin.Latest} is
     * refused rather than resolved: "whatever is current" is not an instruction to publish or discard
     * something.
     */
    Optional<VersionRepository.VersionRow> versionFor(String skillId, VersionPin pin,
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
    String describe(VersionPin pin) {
        return switch (pin) {
            case VersionPin.Named(String version) -> "version " + version;
            case VersionPin.Digest(String sha256Hex) -> "version sha256:" + sha256Hex.substring(0, 8);
            case VersionPin.RowId(String id) -> "row " + id;
            case VersionPin.Latest() -> "the current version";
        };
    }

    static VersionProjection projectionOf(VersionRepository.VersionRow version, String at) {
        return new VersionProjection(version.id(), version.version(), version.digest(),
                version.submittedAt(), version.title(), version.description(),
                version.frontmatter());
    }

    /**
     * When this version first went live. The stored column wins whenever it is set; {@code at} is
     * only the answer for a row this call has just published.
     */
    static String firstLiveAt(VersionRepository.VersionRow version, String at) {
        if (version.firstPublishedAt() != null) {
            return version.firstPublishedAt();
        }
        return version.stateAt() != null ? version.stateAt() : at;
    }

    boolean visibleTo(Access access, String state) {
        return visibleStates(access).contains(state);
    }

    /**
     * The stored states a rung may read on the author plane (ADR 0058).
     *
     * <p>Published is everyone who can see the skill; drafts are {@code write} and up; revoked and
     * discarded rows are {@code maintain} and up. The set moves as one fact so a list and a pinned
     * read never disagree about whether a row exists.
     */
    Set<String> visibleStates(Access access) {
        if (access.atLeast(GrantRole.MAINTAIN)) {
            return Set.of(VersionState.DRAFT, VersionState.PUBLISHED, VersionState.REVOKED,
                    VersionState.DISCARDED);
        }
        if (access.atLeast(GrantRole.WRITE)) {
            return Set.of(VersionState.DRAFT, VersionState.PUBLISHED);
        }
        return Set.of(VersionState.PUBLISHED);
    }

    DraftProjection draftProjectionOf(String skillId) {
        return versions.draftProjectionOf(skillId).orElse(DraftProjection.empty());
    }
}
