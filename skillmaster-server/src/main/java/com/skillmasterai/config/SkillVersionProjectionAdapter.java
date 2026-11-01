package com.skillmasterai.config;

import com.skillmasterai.modules.skill.DraftProjection;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.skill.VersionProjection;
import com.skillmasterai.modules.version.VersionProjectionSink;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Supplies M12's projection writes to M7's {@link VersionProjectionSink}.
 *
 * <p>This lives in {@code config} because that is the one layer allowed to see both modules: M7
 * depends on its own interface, M12 keeps its service, and the coupling is visible in one small
 * adapter instead of being spread through M7.
 */
@Component
final class SkillVersionProjectionAdapter implements VersionProjectionSink {

    private final SkillMetadataService metadata;

    SkillVersionProjectionAdapter(SkillMetadataService metadata) {
        this.metadata = metadata;
    }

    @Override
    public void recordSubmission(String skillId, DraftProjection drafts) {
        metadata.recordSubmission(skillId, drafts);
    }

    @Override
    public void recordDiscard(String skillId, DraftProjection drafts) {
        metadata.recordDiscard(skillId, drafts);
    }

    @Override
    public boolean recordPublish(String skillId, VersionProjection version,
            DraftProjection drafts, String at) {
        return metadata.recordPublish(skillId, version, drafts, at);
    }

    @Override
    public Optional<String> currentVersionId(String skillId) {
        return metadata.currentVersionId(skillId);
    }

    @Override
    public boolean skillDeleted(String skillId) {
        return metadata.skillDeleted(skillId);
    }
}
