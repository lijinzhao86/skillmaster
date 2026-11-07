package com.skillmasterai.usecase.sync;

/** What the writer did with one prepared entry. */
public record WriteResult(String result, String skillId, String versionId, String reason) {

    public static WriteResult created(String skillId, String versionId) {
        return new WriteResult("created", skillId, versionId, null);
    }

    public static WriteResult replayed(String skillId, String versionId) {
        return new WriteResult("unchanged", skillId, versionId, null);
    }

    public static WriteResult blocked(String reason) {
        return new WriteResult("blocked", null, null, reason);
    }
}
