package com.skillmasterai.usecase.sync;

/**
 * The v49 signal array for one repository, already serialised by the adapter.
 *
 * <p>The pipeline does not interpret it: the shape (at most 8 entries, `count` / `date` / `text`,
 * https links) is the adapter's contract, and the same JSON is what a page and `skill show` read.
 */
public record SignalsOk(String signalsJson) implements SignalsResult {
}
