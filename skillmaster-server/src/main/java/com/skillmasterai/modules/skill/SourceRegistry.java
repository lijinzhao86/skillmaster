package com.skillmasterai.modules.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place a source platform is named. Adding a platform is one adapter plus one entry here;
 * nothing else in the application learns its name.
 */
public final class SourceRegistry {

    private final Map<String, SourceAdapter> adapters;

    public SourceRegistry(List<SourceAdapter> adapters) {
        Map<String, SourceAdapter> byKind = new LinkedHashMap<>();
        for (SourceAdapter adapter : adapters) {
            if (byKind.put(adapter.kind(), adapter) != null) {
                throw new IllegalStateException("two source adapters both claim kind " + adapter.kind());
            }
        }
        this.adapters = Map.copyOf(byKind);
    }

    public SourceAdapter get(String kind) {
        SourceAdapter adapter = adapters.get(kind);
        if (adapter == null) {
            throw new InvalidSourceException("unknown source kind: " + kind);
        }
        return adapter;
    }

    public boolean knows(String kind) {
        return adapters.containsKey(kind);
    }
}
