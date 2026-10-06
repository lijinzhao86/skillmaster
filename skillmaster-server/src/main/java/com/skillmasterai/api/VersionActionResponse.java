package com.skillmasterai.api;

/**
 * What publishing or discarding a version did.
 *
 * <p>Small on purpose. The page that made the call already has the version list and re-renders from
 * it; what it cannot know without being told is whether anything actually moved, because a publish
 * of the version that is already live is a success that writes nothing.
 *
 * @param state   the version's state now — {@code published} or {@code discarded}
 * @param liveAt  when it first went live. Null for a discard, which by definition never did, and the
 *                original moment for a version that had been published before — re-publishing is
 *                rollback, and this reports when the version first went live rather than when it was
 *                asked for again
 * @param changed whether anything was written. False on a publish of what was already current: the
 *                state is what was asked for, reached twice
 */
public record VersionActionResponse(int number, String state, String liveAt, boolean changed) {
}
