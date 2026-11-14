package com.skillmasterai.ops.imports;

/**
 * What reading a repository's public signals produced (ADR 0161).
 *
 * <p>Signals are the slow-moving numbers on the source page — stars, forks, the last push. They are
 * read once per repository per run, at the moment a draft is written, so a publish never waits on a
 * third party. A failure is not fatal: the draft is still created and the snapshot stays pending for
 * the daily job to fill.
 */
public sealed interface SignalsResult permits SignalsOk, FetchFailed {
}
