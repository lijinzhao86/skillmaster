package com.skillmasterai.ops.imports;

/** What asking a source for the latest revision produced. */
public sealed interface RevisionResult permits RevisionOk, FetchFailed {
}
