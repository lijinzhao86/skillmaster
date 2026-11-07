package com.skillmasterai.usecase.sync;

import java.util.List;

/** What one run did, entry by entry — the manual trigger's response and the log's source. */
public record SyncReport(String startedAt, String finishedAt, List<SyncEntryResult> entries) {

    public SyncReport {
        entries = List.copyOf(entries);
    }
}
