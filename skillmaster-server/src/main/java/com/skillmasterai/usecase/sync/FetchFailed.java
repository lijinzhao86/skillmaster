package com.skillmasterai.usecase.sync;

/**
 * A source answered with a reason rather than throwing.
 *
 * <p>One broken entry must not stop the run, and a failure is data the log records, not an exception
 * that unwinds the pipeline.
 */
public record FetchFailed(String reason)
        implements ChildrenResult, RevisionResult, FetchResult, SignalsResult {
}
