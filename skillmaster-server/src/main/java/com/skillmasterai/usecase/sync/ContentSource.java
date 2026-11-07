package com.skillmasterai.usecase.sync;

import com.skillmasterai.modules.skill.SourceAdapter.SourceBinding;

/**
 * One platform the sync can read (ADR 0157).
 *
 * <p>The pipeline knows only this interface. Adding a platform is adding an implementation and one
 * line of wiring; no pipeline code, table, endpoint or page changes. An adapter supplies facts —
 * files, a revision and a license file — and never parses {@code SKILL.md}; the base parses that
 * with the same reader a manual upload uses.
 */
public interface ContentSource {

    String kind();

    /**
     * Called once before a run expands anything, and {@link #endRun()} once after it — including
     * when it fails. A source that keeps a per-run cache (one repository archive serving every
     * skill in it, see ADR 0163) opens it here and drops it there; a source that needs neither
     * keeps the empty defaults.
     */
    default void beginRun() {
    }

    /** Called once after the run, whether it finished or threw. */
    default void endRun() {
    }

    /** Whether {@code select: all} can be used on this source. */
    boolean supportsChildren();

    /** How this entry's origin is recorded once its content has been submitted. */
    SourceBinding binding(SyncEntry entry);

    /** The direct subdirectories of the entry's path, for {@code select: all}. */
    ChildrenResult children(SyncEntry entry);

    /** The latest revision that touched the entry's path. */
    RevisionResult revision(SyncEntry entry);

    /**
     * The files under the entry's path, plus the nearest license file.
     *
     * <p>{@code revision} is the one {@link #revision(SyncEntry)} just returned, passed back rather
     * than asked for twice. It is the **change-detection identity** — the skill directory's last
     * commit — and not necessarily the tree the bytes come from: GitHub reads the repository's tip
     * once per run and reports this revision back in the result, because the tip is the only tree it
     * has (ADR 0163). Content and license always come from one tree.
     */
    FetchResult fetch(SyncEntry entry, String revision);

    /**
     * The repository-level signals for this entry's container: stars, forks, the last push.
     *
     * <p>Read once per {@code (kind, locator)} per run, at the moment a draft is written, so the
     * publish path never waits on the source (ADR 0161). A source that cannot answer returns
     * {@link FetchFailed}; the draft is still written and its snapshot stays pending for the daily
     * job.
     */
    SignalsResult signals(SyncEntry entry);
}
