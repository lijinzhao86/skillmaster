package com.skillmasterai.usecase.sync;

/** What the writer did with one prepared entry. */
public record WriteResult(String result, String skillId, String versionId, String reason,
        boolean signalsPending) {

    public static WriteResult created(String skillId, String versionId, boolean signalsPending) {
        return new WriteResult("created", skillId, versionId, null, signalsPending);
    }

    public static WriteResult replayed(String skillId, String versionId, boolean signalsPending) {
        return new WriteResult("unchanged", skillId, versionId, null, signalsPending);
    }

    public static WriteResult blocked(String reason) {
        return new WriteResult("blocked", null, null, reason, false);
    }

    /** Whether this write produced a version, as opposed to replaying one that already existed. */
    public boolean created() {
        return "created".equals(result);
    }
}
