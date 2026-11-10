package com.skillmasterai.modules.activity;

import java.util.Map;

/**
 * One recorded action (v58).
 *
 * <p>The generic shape for every kind except the two that need their own treatment: searches may
 * be merged across pages, and invokes may be attributed to a search. Everything else — a browse,
 * a view, a file read, a download, a sign-in — is this record with a different {@code kind} and a
 * small {@code detail} map.
 *
 * @param ownerUserId the skill owner at write time; null when the event names no skill
 * @param target      the query text or relative path, already truncated by the caller
 * @param detail      kind-specific JSON: the recorder serialises it, the writer never builds a string
 * @param at          RFC3339 UTC, whole seconds, supplied by the caller so tests can pin a window
 */
public record ActivityEvent(
        String actorUserId,
        String ownerUserId,
        String kind,
        String skillId,
        String skillAddress,
        String versionId,
        String versionLabel,
        String target,
        Map<String, Object> detail,
        String at) {

    public ActivityEvent {
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }
}
