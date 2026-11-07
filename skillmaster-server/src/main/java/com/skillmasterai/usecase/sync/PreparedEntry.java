package com.skillmasterai.usecase.sync;

import com.skillmasterai.modules.ingest.SkillDocument;
import com.skillmasterai.modules.skill.SourceAdapter.SourceBinding;
import java.util.List;

/**
 * One entry that passed every gate and is ready to be written.
 *
 * <p>Preparing happens in parallel; the write happens afterwards, one entry at a time. Everything
 * here is a fact the write needs, so the serial section never goes back to the network.
 */
public record PreparedEntry(SyncEntry entry, SourceBinding binding, String revision,
        List<FetchedFile> files, SkillDocument.Document document, LicenseDecision license,
        SecurityReport scan, String platformName, List<String> namingWarnings) {

    public PreparedEntry {
        files = List.copyOf(files);
        namingWarnings = List.copyOf(namingWarnings);
    }
}
