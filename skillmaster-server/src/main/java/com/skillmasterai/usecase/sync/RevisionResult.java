package com.skillmasterai.usecase.sync;

/** What asking a source for the latest revision produced. */
public sealed interface RevisionResult permits RevisionOk, FetchFailed {
}
