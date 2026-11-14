package com.skillmasterai.ops.jobs;

import com.skillmasterai.ops.ports.LabelSource;
import com.skillmasterai.ops.ports.LabelStore;
import com.skillmasterai.ops.ports.RunStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One full refresh: walk the public skills the server lists, re-evaluate each, replace its set.
 *
 * <p><strong>Paged, not one query.</strong> The count is not known up front, and holding every skill
 * in memory buys nothing; the page is also the progress unit if the process dies. A skill fails
 * alone — its entry says so and the next run picks it up.
 */
@Service
public class LabelRefreshJob {

    private static final Logger LOG = LoggerFactory.getLogger(LabelRefreshJob.class);

    /** Small enough that a page is quick, large enough that a run is not thousands of calls. */
    private static final int PAGE_SIZE = 200;

    private final LabelSource source;
    private final LabelStore labels;
    private final LabelRunner runner;
    private final RunStore runs;
    private final RunLock locks;

    public LabelRefreshJob(LabelSource source, LabelStore labels, LabelRunner runner,
            RunStore runs, RunLock locks) {
        this.source = source;
        this.labels = labels;
        this.runner = runner;
        this.runs = runs;
        this.locks = locks;
    }

    /** Same contract as the import: a refusal is a 409 before any work starts. */
    public Optional<Handle> begin(String trigger) {
        Optional<RunLock.Locked> lock = locks.tryAcquire("label", trigger);
        if (lock.isEmpty()) {
            return Optional.empty();
        }
        RunStore.Run run;
        try {
            run = runs.start("label", trigger, Map.of("page_size", PAGE_SIZE));
        } catch (RuntimeException failure) {
            lock.get().close();
            throw failure;
        }
        return Optional.of(new Handle(run, lock.get()));
    }

    public void execute(Handle handle) {
        try (RunLock.Locked held = handle.lock()) {
            execute(handle.run());
        } catch (RuntimeException failure) {
            runs.finish(handle.run().id(), "failed", Map.of(), failure.getMessage());
            throw failure;
        }
    }

    public record Handle(RunStore.Run run, RunLock.Locked lock) {
    }

    private void execute(RunStore.Run run) {
        Map<String, Integer> tally = new LinkedHashMap<>();
        int seen = 0;
        String cursor = null;
        do {
            LabelSource.Page page = source.publicSkills(cursor, PAGE_SIZE);
            for (LabelSource.SkillRef ref : page.items()) {
                seen++;
                tally.merge(labelOne(run, ref), 1, Integer::sum);
            }
            cursor = page.nextCursor();
        } while (cursor != null);

        Map<String, Object> summary = new LinkedHashMap<>(tally);
        summary.put("skills", seen);
        String status = tally.getOrDefault("failed", 0) > 0
                ? (tally.size() == 1 ? "failed" : "partial") : "ok";
        runs.finish(run.id(), status, summary, null);
    }

    private String labelOne(RunStore.Run run, LabelSource.SkillRef ref) {
        LabelRunner.Target target = new LabelRunner.Target(ref.skillId(), ref.namespace(),
                ref.name(), ref.versionId());
        try {
            Optional<LabelSource.Content> content = source.content(ref.namespace(), ref.name());
            if (content.isEmpty()) {
                runner.skip(run.id(), "refresh", target, "content_unavailable");
                return "skipped";
            }
            LabelRunner.Result result = runner.evaluate(run.id(), "refresh", target,
                    content.get().skillMd(), content.get().files(), true);
            return result.result();
        } catch (RuntimeException failure) {
            LOG.warn("label refresh failed for {}", ref.name(), failure);
            return "failed";
        }
    }
}
