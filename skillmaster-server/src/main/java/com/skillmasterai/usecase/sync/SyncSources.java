package com.skillmasterai.usecase.sync;

import java.util.List;

/** The configured entries, expanded or not — a wrapper so the bean has a name of its own. */
public record SyncSources(List<SyncEntry> entries) {

    public SyncSources {
        entries = List.copyOf(entries);
    }
}
