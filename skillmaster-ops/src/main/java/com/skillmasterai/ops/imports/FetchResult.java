package com.skillmasterai.ops.imports;

/** What fetching one entry's content produced. */
public sealed interface FetchResult permits Fetched, FetchFailed {
}
