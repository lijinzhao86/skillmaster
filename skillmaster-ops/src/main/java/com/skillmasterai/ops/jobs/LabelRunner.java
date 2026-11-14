package com.skillmasterai.ops.jobs;

import com.skillmasterai.ops.labels.LabelClaim;
import com.skillmasterai.ops.labels.RuleLabeler;
import com.skillmasterai.ops.labels.SkillFacts;
import com.skillmasterai.ops.ports.LabelSource;
import com.skillmasterai.ops.ports.LabelStore;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * One skill: classify, push, record. Shared by import and refresh so the two paths cannot drift.
 *
 * <p><strong>The order is push then record.</strong> The entry row is what lets the next refresh
 * skip this skill, so it must not be written for a push that failed. Claims are written with the
 * entry either way: the evidence is worth keeping even when the server refused it, and the next
 * run will try the push again because the entry says {@code push_failed}.
 */
@Service
public class LabelRunner {

    private final RuleLabeler labeler = new RuleLabeler();
    private final LabelStore store;
    private final LabelSource source;

    public LabelRunner(LabelStore store, LabelSource source) {
        this.store = store;
        this.source = source;
    }

    public record Target(String skillId, String namespace, String name, String versionId) {
    }

    public record Result(String result, int labels, String note) {
    }

    public Result evaluate(String runId, String origin, Target target, String skillMd,
            List<LabelSource.FileFact> files, boolean skipUnchanged) {
        if (skipUnchanged && store.evaluated(target.skillId(), target.versionId(),
                RuleLabeler.RULE_VERSION)) {
            return new Result("unchanged", 0, null);
        }
        List<LabelClaim> claims;
        try {
            claims = labeler.classify(new SkillFacts(skillMd, files.stream()
                    .map(file -> new SkillFacts.FileFact(file.relpath(), file.size(),
                            file.binary()))
                    .toList()));
        } catch (RuntimeException failure) {
            record(runId, origin, target, List.of(), "failed", 0, message(failure));
            return new Result("failed", 0, message(failure));
        }
        List<String> ids = claims.stream().map(LabelClaim::definitionId).distinct().sorted()
                .toList();
        String result = "labeled";
        String note = null;
        try {
            boolean applied = source.replaceLabels(target.namespace(), target.name(),
                    target.versionId(), ids);
            if (!applied) {
                // The skill already has a different live version. Writing these labels would
                // describe content nobody is serving; the refresh applies them after a publish.
                result = "deferred";
                note = "version_not_current";
            }
        } catch (RuntimeException failure) {
            result = "push_failed";
            note = message(failure);
        }
        record(runId, origin, target, claims, result, ids.size(), note);
        return new Result(result, ids.size(), note);
    }

    /** A skill that vanished or was never readable: one row, and the run moves on. */
    public void skip(String runId, String origin, Target target, String note) {
        record(runId, origin, target, List.of(), "skipped", 0, note);
    }

    private void record(String runId, String origin, Target target, List<LabelClaim> claims,
            String result, int labels, String note) {
        store.record(runId, origin, new LabelStore.Evaluation(target.skillId(), target.namespace(),
                target.name(), target.versionId(), RuleLabeler.RULE_VERSION, result, labels, note,
                Instant.now()), claims);
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
