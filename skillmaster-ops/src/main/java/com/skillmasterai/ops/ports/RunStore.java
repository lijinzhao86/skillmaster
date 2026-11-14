package com.skillmasterai.ops.ports;

import com.skillmasterai.ops.imports.SourceAdapter.SourceBinding;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs and their per-entry outcomes.
 *
 * <p>Generic on purpose: {@code kind} is the only thing that changes when labeling arrives, and
 * the store does not need to know which kinds exist.
 */
public interface RunStore {

    record Run(String id, String kind, String trigger, String status, Map<String, Object> params,
            Map<String, Object> summary, Instant startedAt, Instant finishedAt, String error) {
    }

    record EntryResult(String runId, SourceBinding binding, String revision, String platformName,
            String skillId, String draftId, String result, String note, Instant createdAt) {
    }

    Run start(String kind, String trigger, Map<String, Object> params);

    void finish(String runId, String status, Map<String, Object> summary, String error);

    void recordEntry(EntryResult entry);

    Optional<Run> find(String id);

    /** The per-entry rows of one run — what A6 means by "逐条结果". */
    List<EntryResult> entries(String runId);

    List<Run> recent(String kind, int limit);
}
