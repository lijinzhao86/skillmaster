package com.skillmasterai.usecase.sync;

/** The latest revision that touched the entry's directory. */
public record RevisionOk(String revision) implements RevisionResult {
}
