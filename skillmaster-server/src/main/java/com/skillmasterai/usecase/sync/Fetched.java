package com.skillmasterai.usecase.sync;

import java.util.List;

/**
 * Everything one fetch produced: the files, the revision change detection compares, and the
 * license file.
 *
 * <p>{@code revision} is the skill directory's last commit, not necessarily the tree the bytes
 * came from — GitHub reads the repository tip and reports the directory revision (ADR 0163).
 */
public record Fetched(List<FetchedFile> files, String revision, LicenseFile license)
        implements FetchResult {

    public Fetched {
        files = List.copyOf(files);
    }
}
