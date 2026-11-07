package com.skillmasterai.usecase.sync;

import java.util.List;

/** Everything one fetch produced: the files, the revision they came from, and the license file. */
public record Fetched(List<FetchedFile> files, String revision, LicenseFile license)
        implements FetchResult {

    public Fetched {
        files = List.copyOf(files);
    }
}
