package com.skillmasterai.ops.imports;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The adapters this deployment has, looked up by {@code kind}. */
public final class ContentSourceRegistry {

    private final Map<String, ContentSource> byKind;

    public ContentSourceRegistry(List<ContentSource> sources) {
        Map<String, ContentSource> map = new LinkedHashMap<>();
        for (ContentSource source : sources) {
            ContentSource previous = map.put(source.kind(), source);
            if (previous != null) {
                throw new IllegalStateException("two sync adapters claim kind '" + source.kind() + "'");
            }
        }
        this.byKind = Map.copyOf(map);
    }

    public Optional<ContentSource> get(String kind) {
        return Optional.ofNullable(byKind.get(kind));
    }

    public Set<String> kinds() {
        return byKind.keySet();
    }

    /** Opens every source's per-run state before the pipeline expands anything. */
    public void beginRun() {
        byKind.values().forEach(ContentSource::beginRun);
    }

    /** Drops every source's per-run state, whether the run finished or threw. */
    public void endRun() {
        byKind.values().forEach(ContentSource::endRun);
    }
}
