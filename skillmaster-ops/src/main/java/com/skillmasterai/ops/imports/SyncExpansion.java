package com.skillmasterai.ops.imports;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns the configured whitelist into one entry per skill (v50).
 *
 * <p>{@code select: all} is the only mode that needs the source: it lists the container's direct
 * children and makes each one its own entry, so concurrency, logging and failure attribution are
 * all counted per skill rather than per configured line. Expansion happens before batching.
 */
public final class SyncExpansion {

    private final List<SyncEntry> configured;
    private final ContentSourceRegistry sources;

    public SyncExpansion(List<SyncEntry> configured, ContentSourceRegistry sources) {
        this.configured = List.copyOf(configured);
        this.sources = sources;
    }

    public Expansion expand() {
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
            failures.add(result(entry, "failed", "unknown_kind"));
            return;
        }
        if (!source.get().supportsChildren()) {
            failures.add(result(entry, "failed", "select_all_unsupported"));
            return;
        }
        ChildrenResult children = source.get().children(entry);
        if (children instanceof FetchFailed failed) {
            failures.add(result(entry, "failed", failed.reason()));
            return;
        }
        List<String> names = ((ChildrenOk) children).names().stream()
                .filter(name -> !name.startsWith("."))
                .toList();
        if (names.isEmpty()) {
            failures.add(result(entry, "skipped", "no_children"));
            return;
        }
        names.forEach(name -> entries.add(entry.child(name)));
    }

    private static SyncEntryResult result(SyncEntry entry, String result, String reason) {
        return new SyncEntryResult(entry.kind(), entry.locator(), entry.path(), result, reason,
                null, List.of());
    }

    public record Expansion(List<SyncEntry> entries, List<SyncEntryResult> failures) {
    }
}
