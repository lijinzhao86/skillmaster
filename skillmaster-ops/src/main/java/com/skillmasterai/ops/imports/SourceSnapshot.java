package com.skillmasterai.ops.imports;

/**
 * What one adapter saw for one source at one version.
 *
 * <p>Bound to the version, not to the skill: a later version can come from another commit, and the
 * page must be able to say which commit produced the bytes it is showing.
 */
public record SourceSnapshot(String sourceId, String versionId, String revision, String signalsJson,
        String status, String fetchedAt) {

    public static final String OK = "ok";
    public static final String PENDING = "pending";
    public static final String STALE = "stale";
    public static final String ERROR = "error";
}
