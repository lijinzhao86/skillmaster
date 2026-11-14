package com.skillmasterai.ops.api;

import com.skillmasterai.ops.jobs.ImportJob;
import com.skillmasterai.ops.jobs.LabelRefreshJob;
import com.skillmasterai.ops.ports.LabelStore;
import com.skillmasterai.ops.ports.RunStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The trigger plane. Loopback only; CI reaches it over SSH.
 *
 * <p>Paths are grouped by kind ({@code /import/runs}) so labeling and sampling are new segments
 * under the same shape, not new controllers with their own conventions.
 */
@RestController
@RequestMapping("/inner/v1/jobs")
public class RunController {

    private final ImportJob imports;
    private final LabelRefreshJob labels;
    private final LabelStore labelEntries;
    private final RunStore runs;
    private final ExecutorService worker;

    public RunController(ImportJob imports, LabelRefreshJob labels, LabelStore labelEntries,
            RunStore runs, ExecutorService worker) {
        this.imports = imports;
        this.labels = labels;
        this.labelEntries = labelEntries;
        this.runs = runs;
        this.worker = worker;
    }

    public record StartRequest(String trigger) {
    }

    @PostMapping("/import/runs")
    ResponseEntity<?> start(@RequestBody(required = false) StartRequest body) {
        String trigger = body == null || body.trigger() == null ? "manual" : body.trigger();
        Optional<ImportJob.RunHandle> started = imports.begin(trigger);
        if (started.isEmpty()) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", Map.of("code", "busy")));
        }
        ImportJob.RunHandle handle = started.get();
        try {
            worker.submit(() -> imports.execute(handle));
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            // The executor refused the work (shutdown race); the lock is checked out on a
            // connection, so it has to be closed here or the kind stays locked until restart.
            handle.lock().close();
            runs.finish(handle.run().id(), "failed", Map.of(), "the worker refused the run");
            return ResponseEntity.status(503).body(Map.of("error", Map.of("code", "shutting_down")));
        }
        return ResponseEntity.accepted()
                .body(Map.of("run_id", handle.run().id(), "status", "running"));
    }

    @PostMapping("/label/runs")
    ResponseEntity<?> startLabels(@RequestBody(required = false) StartRequest body) {
        String trigger = body == null || body.trigger() == null ? "manual" : body.trigger();
        Optional<LabelRefreshJob.Handle> started = labels.begin(trigger);
        if (started.isEmpty()) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", Map.of("code", "busy")));
        }
        LabelRefreshJob.Handle handle = started.get();
        try {
            worker.submit(() -> labels.execute(handle));
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            handle.lock().close();
            runs.finish(handle.run().id(), "failed", Map.of(), "the worker refused the run");
            return ResponseEntity.status(503).body(Map.of("error", Map.of("code", "shutting_down")));
        }
        return ResponseEntity.accepted()
                .body(Map.of("run_id", handle.run().id(), "status", "running"));
    }

    @GetMapping("/runs/{id}")
    ResponseEntity<?> run(@PathVariable String id) {
        return runs.find(id)
                .<ResponseEntity<?>>map(run -> {
                    Map<String, Object> detail = view(run);
                    detail.put("entries", "label".equals(run.kind())
                            ? labelEntries.entries(id).stream()
                                    .map(RunController::labelEntryView).toList()
                            : runs.entries(id).stream()
                                    .map(RunController::entryView).toList());
                    return ResponseEntity.ok(detail);
                })
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", Map.of("code", "unknown_run"))));
    }

    @GetMapping("/runs")
    ResponseEntity<?> recent(@RequestParam(defaultValue = "import") String kind,
            @RequestParam(defaultValue = "20") int limit) {
        int bounded = Math.max(1, Math.min(limit, 50));
        List<Map<String, Object>> items = runs.recent(kind, bounded).stream()
                .map(RunController::view)
                .toList();
        return ResponseEntity.ok(Map.of("items", items));
    }

    private static Map<String, Object> entryView(RunStore.EntryResult entry) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", entry.binding().kind());
        value.put("locator", entry.binding().locator());
        value.put("path", entry.binding().path());
        value.put("revision", entry.revision());
        value.put("platform_name", entry.platformName());
        value.put("skill_id", entry.skillId());
        value.put("draft_id", entry.draftId());
        value.put("result", entry.result());
        value.put("note", entry.note());
        value.put("created_at", entry.createdAt().toString());
        return value;
    }

    private static Map<String, Object> labelEntryView(LabelStore.Entry entry) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("skill_id", entry.skillId());
        value.put("namespace", entry.namespace());
        value.put("name", entry.name());
        value.put("version_id", entry.versionId());
        value.put("result", entry.result());
        value.put("labels", entry.labels());
        value.put("note", entry.note());
        value.put("created_at", entry.createdAt().toString());
        return value;
    }

    private static Map<String, Object> view(RunStore.Run run) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", run.id());
        value.put("kind", run.kind());
        value.put("trigger", run.trigger());
        value.put("status", run.status());
        value.put("params", run.params());
        value.put("summary", run.summary());
        value.put("started_at", run.startedAt().toString());
        value.put("finished_at", run.finishedAt() == null ? null : run.finishedAt().toString());
        value.put("error", run.error());
        return value;
    }
}
