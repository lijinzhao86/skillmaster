package com.skillmasterai.ops.imports;

/**
 * The only thing a new source platform has to implement.
 *
 * <p>{@link #parse} validates and normalises a locator without touching the network;
 * {@link #fetch} reads a snapshot. Both return generic values — the rest of the application never
 * learns a platform's name.
 */
public interface SourceAdapter {

    String kind();

    SourceBinding parse(String locator, String path);

    FetchedSource fetch(SourceBinding binding, String revisionHint);

    /** A validated, normalised origin. {@code path} is the skill's subdirectory inside a repo. */
    record SourceBinding(String kind, String locator, String path, String label, String url) {
    }

    /** What one fetch found. Errors are values, not exceptions: a page reads them. */
    record FetchedSource(String revision, String signalsJson, String status,
            LicenseCandidate license) {
    }

    /**
     * A license candidate the adapter read from the source. {@code status} is {@code ok} when the
     * full text is present, {@code missing} when the platform has no license file.
     */
    record LicenseCandidate(String spdx, String name, String copyright, String text,
            String sourcePath, String sourceUrl, String status, String detail) {
    }
}
