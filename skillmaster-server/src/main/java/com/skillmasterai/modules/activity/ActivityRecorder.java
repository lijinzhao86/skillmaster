package com.skillmasterai.modules.activity;

/**
 * M17's write edge (v58).
 *
 * <p>Three entry points, because the three cases are genuinely different:
 *
 * <ul>
 *   <li>{@link #record} appends a generic event;</li>
 *   <li>{@link #recordSearch} inserts a search or merges a continuation into one;</li>
 *   <li>{@link #recordInvoke} attributes the call to a recent search before inserting it.</li>
 * </ul>
 *
 * <p>The methods are synchronous. The caller runs them in a {@code REQUIRES_NEW} transaction and
 * swallows failures: usage history is a side channel, never a reason for the read to fail.
 */
public interface ActivityRecorder {

    void record(ActivityEvent event);

    void recordSearch(SearchActivity search);

    void recordInvoke(InvokeActivity invoke);
}
