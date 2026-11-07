package com.skillmasterai.usecase.sync;

/**
 * One line of the run report.
 *
 * <p>{@code result} is {@code created}, {@code unchanged}, {@code blocked}, {@code failed} or
 * {@code skipped}; {@code reason} is present whenever the entry did not produce a draft.
 */
public record SyncEntryResult(String kind, String locator, String path, String result,
        String reason, String revision, java.util.List<String> warnings) {

    public SyncEntryResult {
        warnings = warnings == null ? java.util.List.of() : java.util.List.copyOf(warnings);
    }
}
