package com.skillmasterai.ops.ports;

import com.skillmasterai.ops.imports.FetchedFile;
import com.skillmasterai.ops.imports.LicenseDecision;
import com.skillmasterai.ops.imports.SourceAdapter.SourceBinding;
import com.skillmasterai.ops.imports.SyncEntry;
import java.util.List;

/**
 * The one way a prepared entry leaves ops.
 *
 * <p>A port rather than a client: the import's tests replace it with a recorder and prove the
 * boundary without a server, and the HTTP adapter stays the only class that knows {@code /inner}.
 */
public interface SubmissionPort {

    /**
     * @param files  the entry's files at their paths inside the skill directory
     * @param license what the license gate decided, including every fact the server stores
     */
    record Submission(SyncEntry entry, SourceBinding binding, String revision, String platformName,
            List<FetchedFile> files, LicenseDecision license, String signalsJson) {
    }

    /** What the server did: a new draft or a replay of an existing one. */
    record Result(String skillId, String draftId, boolean created, boolean replayed) {
    }

    /**
     * @throws com.skillmasterai.ops.jobs.SubmissionRejected when the server refuses the entry
     *         (blocked origin, invalid archive); the message is the server's error code
     */
    Result submit(Submission submission);
}
