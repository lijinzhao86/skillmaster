package com.skillmasterai.ops.ports;

import com.skillmasterai.ops.labels.LabelClaim;
import java.time.Instant;
import java.util.List;

/**
 * Where the evidence lives: the claims of an evaluation and its per-skill result.
 *
 * <p>Both are written together, after the server has accepted (or refused) the push, because the
 * row that lets a refresh skip unchanged work must not exist for a push that never landed.
 */
public interface LabelStore {

    /** True when the latest successful evaluation is this skill, version and rule set. */
    boolean evaluated(String skillId, String versionId, String ruleVersion);

    void record(String runId, String origin, Evaluation evaluation, List<LabelClaim> claims);

    List<Entry> entries(String runId);

    record Evaluation(String skillId, String namespace, String name, String versionId,
            String ruleVersion, String result, int labels, String note, Instant at) {
    }

    record Entry(String skillId, String namespace, String name, String versionId, String result,
            int labels, String note, Instant createdAt) {
    }
}
