package com.skillmasterai.usecase.sync;

import com.skillmasterai.modules.ingest.SkillDocument;
import com.skillmasterai.modules.namespace.Namespace;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.SkillSource;
import com.skillmasterai.modules.skill.SourceAdapter.SourceBinding;
import com.skillmasterai.modules.skill.SourceService;
import com.skillmasterai.modules.version.SkillVersionService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The source-neutral pipeline (ADR 0157): expand the list, fetch and scan in parallel, write in
 * series.
 *
 * <p><strong>Why the split is exactly there.</strong> Fetching, parsing and scanning are network and
 * CPU work that scales with {@code concurrency}. Writing cannot: a submission takes a table lock on
 * {@code version_file}, so parallel writes would queue on the same lock while holding extra
 * connections — and would make the log and the failure attribution unreadable. A batch therefore
 * finishes completely before any of its entries is written.
 *
 * <p><strong>Nothing here names GitHub.</strong> The adapters are looked up by {@code kind}; this
 * class never imports one.
 */
public final class SyncPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(SyncPipeline.class);

    private final List<SyncEntry> configured;
    private final ContentSourceRegistry sources;
    private final SourceService sourceFacts;
    private final SkillVersionService versions;
    private final NamespaceService namespaces;
    private final LicenseGate licenses;
    private final SecurityScanner scanner;
    private final SyncDraftWriter writer;
    private final int concurrency;
    private final int maxFiles;
    private final long maxFileBytes;
    private final long maxTotalBytes;

    public SyncPipeline(List<SyncEntry> configured, ContentSourceRegistry sources,
            SourceService sourceFacts, SkillVersionService versions, NamespaceService namespaces,
            LicenseGate licenses, SecurityScanner scanner, SyncDraftWriter writer, int concurrency,
            int maxFiles, long maxFileBytes, long maxTotalBytes) {
        this.configured = List.copyOf(configured);
        this.sources = sources;
        this.sourceFacts = sourceFacts;
        this.versions = versions;
        this.namespaces = namespaces;
        this.licenses = licenses;
        this.scanner = scanner;
        this.writer = writer;
        this.concurrency = concurrency;
        this.maxFiles = maxFiles;
        this.maxFileBytes = maxFileBytes;
        this.maxTotalBytes = maxTotalBytes;
    }

    public SyncReport run() {
        String startedAt = Instant.now().toString();
        Namespace official = namespaces.official().orElseThrow(() -> new IllegalStateException(
                "no namespace is marked official; the sync has nowhere to write"));
        /* The submission path resolves its target from the subject's *personal* namespace. The
           preflight and cleanup use the namespace marked official. They are the same row only
           because the seed makes them so; a deployment that marks a different row is a defect
           this refuses rather than writing content into one namespace and checking another. */
        Namespace personal = namespaces.personalNamespaceOf(official.ownerUserId());
        if (!personal.id().equals(official.id())) {
            throw new IllegalStateException("the official namespace is not the official account's"
                    + " personal namespace; the sync refuses to write");
        }
        Expansion expansion = expand();
        List<SyncEntryResult> results = new ArrayList<>();
        expansion.failures().forEach(failure -> {
            results.add(failure);
            log(failure);
        });
        Set<String> addresses = new HashSet<>();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            for (int start = 0; start < expansion.entries().size(); start += concurrency) {
                List<SyncEntry> batch = expansion.entries().subList(start,
                        Math.min(start + concurrency, expansion.entries().size()));
                for (SyncEntryResult result : runBatch(batch, official, addresses, pool)) {
                    results.add(result);
                    log(result);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return new SyncReport(startedAt, Instant.now().toString(), results);
    }

    private List<SyncEntryResult> runBatch(List<SyncEntry> batch, Namespace official,
            Set<String> addresses, ExecutorService pool) {
        List<Callable<Outcome>> tasks = batch.stream()
                .map(entry -> (Callable<Outcome>) () -> prepare(entry, official))
                .toList();
        List<Future<Outcome>> futures;
        try {
            futures = pool.invokeAll(tasks);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return batch.stream()
                    .map(entry -> result(entry, "failed", "interrupted", null))
                    .toList();
        }
        List<SyncEntryResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            SyncEntry entry = batch.get(i);
            Outcome outcome;
            try {
                outcome = futures.get(i).get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                results.add(result(entry, "failed", "interrupted", null));
                continue;
            } catch (ExecutionException failure) {
                results.add(result(entry, "failed", "internal_error", null));
                continue;
            }
            try {
                results.add(outcome.ok()
                        ? write(outcome, official, addresses)
                        : result(entry, outcome.result(), outcome.reason(), outcome.revision(),
                                outcome.warnings()));
            } catch (RuntimeException failure) {
                /* One entry's write must not take the run down. The transaction has rolled back by
                   now, so this entry produced nothing; the next entries and the next run are intact. */
                LOG.warn("sync entry {} failed while writing", entry.display(), failure);
                results.add(result(entry, "failed", "write_failed", outcome.revision()));
            }
        }
        return results;
    }

    private SyncEntryResult write(Outcome outcome, Namespace official, Set<String> addresses) {
        PreparedEntry prepared = outcome.prepared();
        String address = prepared.platformName();
        if (!addresses.add(address)) {
            return result(outcome.entry(), "blocked", "duplicate_address", outcome.revision());
        }
        WriteResult written = writer.write(prepared, official);
        List<String> warnings = new ArrayList<>(prepared.namingWarnings());
        prepared.scan().warnings().stream()
                .map(finding -> finding.rule() + ":" + finding.path())
                .forEach(warnings::add);
        return new SyncEntryResult(outcome.entry().kind(), outcome.entry().locator(),
                outcome.entry().path(), written.result(), written.reason(), outcome.revision(),
                warnings);
    }

    /** Everything up to the write: network, parsing, the gates. Safe to run in parallel. */
    private Outcome prepare(SyncEntry entry, Namespace official) {
        try {
            Optional<ContentSource> source = sources.get(entry.kind());
            if (source.isEmpty()) {
                return Outcome.failed(entry, "unknown_kind", null);
            }
            RevisionResult revisionResult = source.get().revision(entry);
            if (revisionResult instanceof FetchFailed failed) {
                return absent(failed.reason())
                        ? Outcome.skipped(entry, failed.reason(), null)
                        : gate(failed.reason())
                                ? Outcome.blocked(entry, failed.reason(), null)
                                : Outcome.failed(entry, failed.reason(), null);
            }
            String revision = ((RevisionOk) revisionResult).revision();
            Optional<SourceService.SyncAnchor> anchor = anchorFor(entry, official);
            if (anchor.isPresent() && revision.equals(anchor.get().revision())) {
                return new Outcome(entry, null, "unchanged", null, revision, List.of());
            }

            FetchResult fetchResult = source.get().fetch(entry, revision);
            if (fetchResult instanceof FetchFailed failed) {
                return absent(failed.reason())
                        ? Outcome.skipped(entry, failed.reason(), revision)
                        : gate(failed.reason())
                                ? Outcome.blocked(entry, failed.reason(), revision)
                                : Outcome.failed(entry, failed.reason(), revision);
            }
            Fetched fetched = (Fetched) fetchResult;
            if (fetched.files().size() > maxFiles) {
                return Outcome.blocked(entry, "too_large", revision);
            }
            long total = 0;
            for (FetchedFile file : fetched.files()) {
                if (file.content().length > maxFileBytes) {
                    return Outcome.blocked(entry, "too_large", revision);
                }
                total += file.content().length;
                if (total > maxTotalBytes) {
                    return Outcome.blocked(entry, "too_large", revision);
                }
            }

            FetchedFile skillMd = findSkillMd(entry, fetched.files());
            if (skillMd == null) {
                return Outcome.skipped(entry, "missing_skill_md", revision);
            }
            SkillDocument.Document document;
            try {
                document = SkillDocument.parse(skillMd.content());
            } catch (RuntimeException invalid) {
                return Outcome.failed(entry, "invalid_skill_md", revision);
            }

            SourceBinding binding = source.get().binding(entry);
            String requested = SkillNameTransform.platformName(document.name(), entry.naming());
            List<String> namingWarnings = new ArrayList<>();
            Optional<String> stable = sourceFacts.syncedName(official.id(), entry.kind(),
                    entry.locator(), entry.path());
            String platformName;
            if (stable.isPresent()) {
                platformName = stable.get();
                if (!platformName.equals(requested)) {
                    namingWarnings.add("address_kept:existing=" + platformName
                            + " requested=" + requested);
                }
            } else {
                platformName = requested;
                try {
                    SkillNameTransform.requireValid(platformName);
                } catch (SkillNameTransform.InvalidName invalid) {
                    return Outcome.blocked(entry, invalid.reason(), revision, namingWarnings);
                }
            }
            Optional<SkillSource> held = sourceFacts.findByAddress(official.id(), platformName);
            if (held.isPresent() && !sameOrigin(held.get(), binding)) {
                return Outcome.blocked(entry, "origin_conflict", revision, namingWarnings);
            }
            /* The rewrite has to be proved here, not left to the writer: a frontmatter the line
               rewrite cannot edit is a defect in the content, and finding it inside the write
               transaction would report it as write_failed with a stack trace. A document that
               parses but cannot be rewritten is its own reason — it is not invalid content. */
            if (!platformName.equals(document.name())) {
                try {
                    SkillNameTransform.rewriteSkillMd(skillMd.content(), platformName);
                } catch (SkillNameTransform.NotRewritable unrewritable) {
                    return Outcome.failed(entry, "name_not_rewritable", revision, namingWarnings);
                } catch (IllegalStateException | com.skillmasterai.modules.ingest.IngestException
                        unreadable) {
                    return Outcome.failed(entry, "invalid_skill_md", revision, namingWarnings);
                }
            }

            LicenseDecision license = licenses.decide(fetched.license(),
                    declaredLicense(document));
            /* A license that disappears is a different fact from one that was never there. The
               draft's note is the operator's only pre-publish signal, so it says "已取消" when the
               previous version of this skill carried one. */
            if (license.status() == com.skillmasterai.modules.skill.LicenseStatus.MISSING
                    && anchor.isPresent()
                    && sourceFacts.license(anchor.get().versionId())
                            .map(previous -> previous.status()
                                    != com.skillmasterai.modules.skill.LicenseStatus.MISSING)
                            .orElse(false)) {
                license = new LicenseDecision(LicenseDecision.Action.SYNC_EMPTY, license.spdx(),
                        license.name(), license.text(), license.path(), license.url(),
                        license.copyright(), license.status(), license.detail(), "（已取消）");
            }
            if (!license.allowed()) {
                return Outcome.blocked(entry, license.note(), revision, namingWarnings);
            }

            SecurityReport scan = scanner.scan(fetched.files());
            if (scan.hasBlocking()) {
                return Outcome.blocked(entry, "scan_blocked:" + scan.blocked().get(0).rule(),
                        revision, namingWarnings);
            }
            scan = withLimitWarnings(fetched.files(), scan);

            return new Outcome(entry, new PreparedEntry(entry, binding, revision, fetched.files(),
                    document, license, scan, platformName, namingWarnings), null, null, revision,
                    namingWarnings);
        } catch (RuntimeException failure) {
            LOG.warn("sync entry {} failed while preparing", entry.display(), failure);
            return Outcome.failed(entry, "internal_error", null);
        }
    }

    /**
     * The snapshot belonging to the newest version of the skill, if there is one.
     *
     * <p>M12 supplies every snapshot for the origin; M7 supplies the newest version id. Pairing
     * them here is what lets a discarded or revoked revision count as seen without M12 reading
     * M7's table — and without a later daily refresh of an older live version moving the anchor.
     */
    private Optional<SourceService.SyncAnchor> anchorFor(SyncEntry entry, Namespace official) {
        List<SourceService.SyncAnchor> candidates = sourceFacts.anchors(official.id(),
                entry.kind(), entry.locator(), entry.path());
        Set<String> skills = candidates.stream()
                .map(SourceService.SyncAnchor::skillId)
                .collect(java.util.stream.Collectors.toSet());
        if (skills.size() != 1) {
            /* No skill, or more than one holding the same origin (an upstream rename can leave
               both). Guessing which one is "the" skill could skip a skill that still needs its
               first draft; downloading and letting the address decide is the safe answer. */
            return Optional.empty();
        }
        String skillId = skills.iterator().next();
        List<String> versionIds = candidates.stream()
                .map(SourceService.SyncAnchor::versionId)
                .toList();
        return versions.newestVersionIdAmong(skillId, versionIds)
                .flatMap(newest -> candidates.stream()
                        .filter(candidate -> candidate.versionId().equals(newest))
                        .findFirst());
    }

    private SecurityReport withLimitWarnings(List<FetchedFile> files, SecurityReport scan) {
        List<SecurityFinding> warnings = new ArrayList<>(scan.warnings());
        long total = files.stream().mapToLong(file -> file.content().length).sum();
        if (files.size() >= maxFiles * 0.9) {
            warnings.add(SecurityScanner.nearLimit("文件数"));
        }
        if (maxTotalBytes > 0 && total >= maxTotalBytes * 0.9) {
            warnings.add(SecurityScanner.nearLimit("总字节"));
        }
        return new SecurityReport(scan.blocked(), warnings);
    }

    /** An absent path is an expected skip, not a failure of the run. */
    private static boolean absent(String reason) {
        return "not_found".equals(reason) || "no_commits".equals(reason);
    }

    /** A content gate said no; this is a block, not a transient failure worth retrying. */
    private static boolean gate(String reason) {
        return "too_large".equals(reason) || "unsafe_archive".equals(reason)
                || "license_path_missing".equals(reason);
    }

    private static boolean sameOrigin(SkillSource source, SourceBinding binding) {
        return source.kind().equals(binding.kind())
                && source.locator().equals(binding.locator())
                && source.path().equals(binding.path());
    }

    private static FetchedFile findSkillMd(SyncEntry entry, List<FetchedFile> files) {
        String wanted = entry.path().isEmpty()
                ? "SKILL.md"
                : entry.path() + "/SKILL.md";
        return files.stream().filter(file -> file.relpath().equals(wanted)).findFirst().orElse(null);
    }

    /**
     * The declaration, with the same precedence v49 uses: {@code metadata.license} first, then the
     * top-level field. A value that is not text is not "no declaration" — it is an unrecognised
     * one, so it becomes {@code NOASSERTION} and the gate blocks it rather than syncing empty.
     */
    private static String declaredLicense(SkillDocument.Document document) {
        Map<String, Object> frontmatter = document.frontmatter();
        Object nested = frontmatter.get("metadata");
        if (nested instanceof Map<?, ?> metadata) {
            String value = declared(metadata.get("license"));
            if (value != null) {
                return value;
            }
        }
        return declared(frontmatter.get("license"));
    }

    private static String declared(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof String text
                ? (text.isBlank() ? null : text.trim())
                : "NOASSERTION";
    }

    private Expansion expand() {
        List<SyncEntry> entries = new ArrayList<>();
        List<SyncEntryResult> failures = new ArrayList<>();
        for (SyncEntry configuredEntry : configured) {
            switch (configuredEntry.mode()) {
                case ONE -> entries.add(configuredEntry);
                case NAMES -> configuredEntry.names()
                        .forEach(name -> entries.add(configuredEntry.child(name)));
                case ALL -> expandAll(configuredEntry, entries, failures);
            }
        }
        return new Expansion(entries, failures);
    }

    private void expandAll(SyncEntry entry, List<SyncEntry> entries,
            List<SyncEntryResult> failures) {
        Optional<ContentSource> source = sources.get(entry.kind());
        if (source.isEmpty()) {
            failures.add(result(entry, "failed", "unknown_kind", null));
            return;
        }
        if (!source.get().supportsChildren()) {
            failures.add(result(entry, "failed", "select_all_unsupported", null));
            return;
        }
        ChildrenResult children = source.get().children(entry);
        if (children instanceof FetchFailed failed) {
            failures.add(result(entry, "failed", failed.reason(), null));
            return;
        }
        List<String> names = ((ChildrenOk) children).names().stream()
                .filter(name -> !name.startsWith("."))
                .toList();
        if (names.isEmpty()) {
            failures.add(result(entry, "skipped", "no_children", null));
            return;
        }
        names.forEach(name -> entries.add(entry.child(name)));
    }

    private static SyncEntryResult result(SyncEntry entry, String result, String reason,
            String revision) {
        return result(entry, result, reason, revision, List.of());
    }

    private static SyncEntryResult result(SyncEntry entry, String result, String reason,
            String revision, List<String> warnings) {
        return new SyncEntryResult(entry.kind(), entry.locator(), entry.path(), result, reason,
                revision, warnings);
    }

    private static void log(SyncEntryResult result) {
        String entry = result.kind() + ":" + result.locator()
                + (result.path() == null || result.path().isEmpty() ? "" : "@" + result.path());
        LOG.info("sync entry={} result={} reason={} revision={} warnings={}", entry,
                result.result(),
                result.reason() == null ? "" : result.reason(),
                result.revision() == null ? "" : result.revision(),
                result.warnings().isEmpty() ? "" : String.join(",", result.warnings()));
    }

    private record Expansion(List<SyncEntry> entries, List<SyncEntryResult> failures) {
    }

    private record Outcome(SyncEntry entry, PreparedEntry prepared, String result, String reason,
            String revision, List<String> warnings) {

        static Outcome failed(SyncEntry entry, String reason, String revision) {
            return new Outcome(entry, null, "failed", reason, revision, List.of());
        }

        static Outcome failed(SyncEntry entry, String reason, String revision,
                List<String> warnings) {
            return new Outcome(entry, null, "failed", reason, revision, List.copyOf(warnings));
        }

        static Outcome blocked(SyncEntry entry, String reason, String revision) {
            return new Outcome(entry, null, "blocked", reason, revision, List.of());
        }

        static Outcome blocked(SyncEntry entry, String reason, String revision,
                List<String> warnings) {
            return new Outcome(entry, null, "blocked", reason, revision, List.copyOf(warnings));
        }

        static Outcome skipped(SyncEntry entry, String reason, String revision) {
            return new Outcome(entry, null, "skipped", reason, revision, List.of());
        }

        boolean ok() {
            return prepared != null;
        }
    }
}
