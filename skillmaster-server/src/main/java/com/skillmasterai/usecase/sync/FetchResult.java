package com.skillmasterai.usecase.sync;

/** What fetching one entry's content produced. */
public sealed interface FetchResult permits Fetched, FetchFailed {
}
