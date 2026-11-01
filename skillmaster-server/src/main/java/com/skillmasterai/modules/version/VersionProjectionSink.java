package com.skillmasterai.modules.version;

import com.skillmasterai.modules.skill.DraftProjection;
import com.skillmasterai.modules.skill.VersionProjection;
import java.util.Optional;

/**
 * The seam M7 uses to ask M12 to write the skill row's projections.
 *
 * <p><strong>Why a port instead of calling {@code SkillMetadataService} directly.</strong> M7
 * computes the draft / version projections because it owns the versions; M12 writes them because it
 * owns the skill row (ADR 0053). A direct call makes M7 compile-depend on M12's service, which is
 * the one dependency that stops M7 being extractable on its own. The port keeps the direction:
 * M7 depends on its own interface, and the adapter in {@code config} — the wiring layer, which is
 * allowed to see both modules — supplies M12's service.
 *
 * <p>The value types in the signatures ({@link DraftProjection}, {@link VersionProjection}) are
 * published by M12 and listed in {@code ModuleMap}; the <em>service</em> is not.
 */
public interface VersionProjectionSink {

    void recordSubmission(String skillId, DraftProjection drafts);

    void recordDiscard(String skillId, DraftProjection drafts);

    /**
     * Moves the pointer and writes the projections.
     *
     * <p><strong>A {@code false} has two meanings since v37</strong>: the pointer already named this
     * version (nothing to do), or the skill row was deleted and the statement's
     * {@code deleted_at IS NULL} kept it from matching. Check {@link #skillDeleted} — treating the
     * second as "nothing to do" would commit a live version on a deleted skill (ADR 0127).
     */
    boolean recordPublish(String skillId, VersionProjection version, DraftProjection drafts,
            String at);

    Optional<String> currentVersionId(String skillId);

    /**
     * Whether the skill row is soft-deleted.
     *
     * <p>**The fact a publish needs after a zero-row {@link #recordPublish}.** A delete that wins the
     * row lock makes that statement match nothing (its {@code WHERE} excludes deleted rows), and
     * returning "unchanged" there would let the publish commit a live version on a skill nobody can
     * reach. The caller rolls the transaction back instead (ADR 0127).
     */
    boolean skillDeleted(String skillId);
}
