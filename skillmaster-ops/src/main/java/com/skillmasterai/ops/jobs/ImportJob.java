package com.skillmasterai.ops.jobs;

import com.skillmasterai.ops.imports.ContentSource;
import com.skillmasterai.ops.imports.ContentSourceRegistry;
import com.skillmasterai.ops.imports.FetchFailed;
import com.skillmasterai.ops.imports.Fetched;
import com.skillmasterai.ops.imports.FetchedFile;
import com.skillmasterai.ops.imports.LicenseDecision;
import com.skillmasterai.ops.imports.LicenseGate;
import com.skillmasterai.ops.imports.SecurityReport;
import com.skillmasterai.ops.imports.SecurityScanner;
import com.skillmasterai.ops.imports.SignalsOk;
import com.skillmasterai.ops.imports.SkillDocument;
import com.skillmasterai.ops.imports.SkillNameTransform;
import com.skillmasterai.ops.imports.SourceAdapter.SourceBinding;
import com.skillmasterai.ops.imports.SyncEntry;
import com.skillmasterai.ops.imports.SyncEntryResult;
import com.skillmasterai.ops.imports.SyncExpansion;
import com.skillmasterai.ops.imports.SyncSources;
import com.skillmasterai.ops.ports.LabelSource;
import com.skillmasterai.ops.ports.RunStore;
import com.skillmasterai.ops.ports.SubmissionPort;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One import run: expand, fetch and scan in parallel, submit in series.
 *
 * <p>The split is deliberate. Fetching is network-bound and safe to parallelise; submitting takes
 * the same write path a person's upload takes, and doing those in parallel would only queue on the
 * server's locks while making the log unreadable.
 */
@Service
public class ImportJob {

    private static final Logger LOG = LoggerFactory.getLogger(ImportJob.class);

    private final SyncSources sources;
    private final ContentSourceRegistry registry;
    private final SecurityScanner scanner;
    private final LicenseGate licenses;
    private final SubmissionPort submissions;
    private final RunStore runs;
    private final RunLock locks;
    private final LabelRunner labels;
    private final int concurrency;
    private final String officialNamespace;

    public ImportJob(SyncSources sources, ContentSourceRegistry registry, SecurityScanner scanner,
            LicenseGate licenses, SubmissionPort submissions, RunStore runs, RunLock locks,
            LabelRunner labels, com.skillmasterai.ops.config.OpsProperties properties) {
        this.sources = sources;
        this.registry = registry;
        this.scanner = scanner;
        this.licenses = licenses;
        this.submissions = submissions;
        this.runs = runs;
        this.locks = locks;
        this.labels = labels;
        this.concurrency = Math.max(1, properties.concurrency());
        this.officialNamespace = properties.officialNamespace();
    }

    /**
     * Take the lock and create the run row, so a refusal is a 409 before any work starts.
     *
     * <p>The returned handle owns the lock; the caller runs {@link #execute(RunHandle)} on a
     * worker thread and the lock is released when that call returns.
     */
    public Optional<RunHandle> begin(String trigger) {
        Optional<RunLock.Locked> lock = locks.tryAcquire("import", trigger);
        if (lock.isEmpty()) {
            return Optional.empty();
        }
        RunStore.Run run;
        try {
            run = runs.start("import", trigger, Map.of());
        } catch (RuntimeException failure) {
            // The lock is a checked-out connection with an open transaction; leaking it would
            // keep the row locked for the life of the process.
            lock.get().close();
            throw failure;
        }
        return Optional.of(new RunHandle(run, lock.get()));
    }

    /** Runs the import; the lock in the handle is released when this returns. */
    public void execute(RunHandle handle) {
        try (RunLock.Locked held = handle.lock()) {
            execute(handle.run());
        } catch (RuntimeException failure) {
            runs.finish(handle.run().id(), "failed", Map.of(), failure.getMessage());
            throw failure;
        }
    }

    public record RunHandle(RunStore.Run run, RunLock.Locked lock) {
    }

    private void execute(RunStore.Run run) {
        SyncExpansion.Expansion expansion = new SyncExpansion(sources.entries(), registry).expand();
        List<SyncEntry> entries = expansion.entries();

        Set<String> kinds = entries.stream().map(SyncEntry::kind).collect(java.util.stream.Collectors.toSet());
        for (String kind : kinds) {
            registry.get(kind).ifPresent(ContentSource::beginRun);
        }

        Map<String, Integer> tally = new LinkedHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            java.util.concurrent.ConcurrentMap<String, String> signalCache =
                    new java.util.concurrent.ConcurrentHashMap<>();
            // Batches, not one submission per entry: a completed Ready holds the entry's bytes,
            // and keeping every Future alive would hold the whole run in memory.
            for (int start = 0; start < entries.size(); start += concurrency) {
                List<SyncEntry> batch = entries.subList(start,
                        Math.min(start + concurrency, entries.size()));
                List<Future<Prepared>> futures = new ArrayList<>();
                for (SyncEntry entry : batch) {
                    futures.add(pool.submit(() -> prepare(entry, signalCache)));
                }
                for (int index = 0; index < batch.size(); index++) {
                    Prepared prepared = await(futures.get(index));
                    tally.merge(dispose(run, batch.get(index), prepared).result(), 1, Integer::sum);
                }
            }
        } finally {
            pool.shutdownNow();
            for (String kind : kinds) {
                registry.get(kind).ifPresent(ContentSource::endRun);
            }
        }

        for (SyncEntryResult failure : expansion.failures()) {
            tally.merge(failure.result(), 1, Integer::sum);
        }
        String status = tally.getOrDefault("failed", 0) > 0 && tally.getOrDefault("created", 0) == 0
                && tally.getOrDefault("replayed", 0) == 0 ? "failed" : "ok";
        if (tally.getOrDefault("failed", 0) > 0 && tally.size() > 1) {
            status = "partial";
        }
        Map<String, Object> summary = new LinkedHashMap<>(tally);
        summary.put("entries", entries.size());
        runs.finish(run.id(), status, summary, null);
    }

    private Disposed dispose(RunStore.Run run, SyncEntry entry, Prepared prepared) {
        if (prepared instanceof Failed failed) {
            record(run, entry, failed.binding(), failed.revision(), failed.platformName(), null, null,
                    "failed", failed.reason());
            return new Disposed("failed");
        }
        if (prepared instanceof Blocked blocked) {
            record(run, entry, blocked.binding(), blocked.revision(), blocked.platformName(), null,
                    null, "blocked", blocked.reason());
            return new Disposed("blocked");
        }
        Ready ready = (Ready) prepared;
        try {
            SubmissionPort.Result result = submissions.submit(new SubmissionPort.Submission(
                    entry, ready.binding(), ready.revision(), ready.platformName(), ready.files(),
                    ready.license(), ready.signals()));
            label(run, ready, result);
            String outcome = result.replayed() ? "replayed" : "created";
            record(run, entry, ready.binding(), ready.revision(), ready.platformName(),
                    result.skillId(), result.draftId(), outcome, null);
            return new Disposed(outcome);
        } catch (RuntimeException failure) {
            record(run, entry, ready.binding(), ready.revision(), ready.platformName(), null, null,
                    "failed", message(failure));
            return new Disposed("failed");
        }
    }

    private Prepared prepare(SyncEntry entry, java.util.concurrent.ConcurrentMap<String, String> signals) {
        Optional<ContentSource> source = registry.get(entry.kind());
        if (source.isEmpty()) {
            return new Failed(entry.kind(), entry.locator(), entry.path(), null, null,
                    "unknown_kind");
        }
        ContentSource content = source.get();
        SourceBinding binding = new SourceBinding(entry.kind(), entry.locator(), entry.path(), "", "");
        String revision = null;
        String platformName = null;
        try {
            binding = content.binding(entry);
            if (!(content.revision(entry) instanceof com.skillmasterai.ops.imports.RevisionOk ok)) {
                return new Failed(binding, null, null, "no_revision");
            }
            revision = ok.revision();
            if (!(content.fetch(entry, revision) instanceof Fetched fetched)) {
                return new Failed(binding, revision, null, "fetch_failed");
            }
            List<FetchedFile> files = relativeTo(entry.path(), fetched.files());
            SkillDocument.Document document = SkillDocument.parse(skillMd(files));
            platformName = SkillNameTransform.platformName(document.name(), entry.naming());
            SkillNameTransform.requireValid(platformName);

            SecurityReport scan = scanner.scan(files, entry.path());
            if (scan.hasBlocking()) {
                return new Blocked(binding, revision, platformName,
                        "scan_blocked:" + scan.blocked().get(0).rule());
            }
            LicenseDecision license = licenses.decide(fetched.license(), declaredLicense(document));
            if (!license.allowed()) {
                return new Blocked(binding, revision, platformName, license.note());
            }
            String key = entry.kind() + "|" + entry.locator();
            String signalsJson = signals.computeIfAbsent(key, ignored ->
                    content.signals(entry) instanceof SignalsOk read ? read.signalsJson() : "");
            return new Ready(entry, binding, revision, platformName, files, license,
                    signalsJson.isEmpty() ? null : signalsJson);
        } catch (SkillNameTransform.InvalidName invalid) {
            return new Failed(binding, revision, platformName, invalid.getMessage());
        } catch (SkillNameTransform.NotRewritable notRewritable) {
            return new Failed(binding, revision, platformName, "name_not_rewritable");
        } catch (IllegalStateException | com.skillmasterai.ops.imports.IngestException unreadable) {
            return new Failed(binding, revision, platformName, "invalid_skill_md");
        } catch (com.skillmasterai.ops.imports.InvalidSourceException invalidLocator) {
            return new Failed(binding, revision, platformName, "invalid_locator");
        }
    }

    /** Files at their paths inside the skill directory; the zip root is added later. */
    private static List<FetchedFile> relativeTo(String directory, List<FetchedFile> files) {
        String prefix = directory == null || directory.isBlank() ? "" : directory + "/";
        return files.stream()
                .filter(file -> file.relpath().startsWith(prefix))
                .map(file -> new FetchedFile(file.relpath().substring(prefix.length()),
                        file.content(), file.executable()))
                .toList();
    }

    private static byte[] skillMd(List<FetchedFile> files) {
        return files.stream()
                .filter(file -> file.relpath().equals("SKILL.md")
                        || file.relpath().endsWith("/SKILL.md"))
                .min(java.util.Comparator.comparingInt(file -> file.relpath().length()))
                .map(FetchedFile::content)
                .orElseThrow(() -> new IllegalStateException("no SKILL.md in the entry"));
    }

    private static String declaredLicense(SkillDocument.Document document) {
        Object value = document.frontmatter().get("license");
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Label the version that was just submitted. Deliberately after the submission succeeded and
     * outside its failure: a label push that fails does not take the draft with it, and the entry
     * row says {@code push_failed} so the next run tries again.
     */
    private void label(RunStore.Run run, Ready ready, SubmissionPort.Result result) {
        try {
            byte[] markdown = skillMd(ready.files());
            List<LabelSource.FileFact> manifest = ready.files().stream()
                    .map(file -> new LabelSource.FileFact(file.relpath(), file.content().length,
                            false))
                    .toList();
            labels.evaluate(run.id(), "import", new LabelRunner.Target(result.skillId(),
                    officialNamespace, ready.platformName(), result.draftId()),
                    new String(markdown, StandardCharsets.UTF_8), manifest, true);
        } catch (RuntimeException failure) {
            LOG.warn("labeling {} failed after submit", ready.platformName(), failure);
        }
    }

    private void record(RunStore.Run run, SyncEntry entry, SourceBinding binding, String revision,
            String platformName, String skillId, String draftId, String result, String note) {
        try {
            runs.recordEntry(new RunStore.EntryResult(run.id(), binding, revision,
                    platformName == null ? "" : platformName, skillId, draftId, result, note,
                    Instant.now()));
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            // A whitelist can reach one skill twice; the second row is a duplicate, not a reason
            // to abandon the rest of the run.
            LOG.warn("import entry already recorded: {} {} {}", binding.locator(), revision,
                    platformName);
        }
    }

    private static Prepared await(Future<Prepared> future) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Failed("", "", "", null, null, "interrupted");
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            LOG.warn("import entry failed", cause);
            return new Failed("", "", "", null, null, message(cause));
        }
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static boolean matches(SyncEntry entry, String address) {
        String[] parts = address.split("/", 2);
        return parts.length == 2 && entry.kind().equals(parts[0]);
    }

    private record Disposed(String result) {
    }

    private sealed interface Prepared permits Ready, Blocked, Failed {
    }

    private record Ready(SyncEntry entry, SourceBinding binding, String revision, String platformName,
            List<FetchedFile> files, LicenseDecision license, String signals) implements Prepared {
    }

    private record Blocked(SourceBinding binding, String revision, String platformName,
            String reason) implements Prepared {
    }

    private record Failed(SourceBinding binding, String revision, String platformName,
            String reason) implements Prepared {
        Failed(String kind, String locator, String path, String revision, String platformName,
                String reason) {
            this(new SourceBinding(kind, locator, path, "", ""), revision, platformName, reason);
        }
    }
}
