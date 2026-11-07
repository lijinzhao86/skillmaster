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

    /** Whether {@code select: all} can be used on this source. */
    boolean supportsChildren();

    /** How this entry's origin is recorded once its content has been submitted. */
    SourceBinding binding(SyncEntry entry);

    /** The direct subdirectories of the entry's path, for {@code select: all}. */
    ChildrenResult children(SyncEntry entry);

    /** The latest revision that touched the entry's path. */
    RevisionResult revision(SyncEntry entry);

    /**
     * The files under the entry's path at {@code revision}, plus the nearest license file.
     *
     * <p>The revision is the one {@link #revision(SyncEntry)} just returned, passed back rather than
     * asked for twice: the content and its license have to come from the same commit, and a second
     * lookup could answer with a newer one.
     */
    FetchResult fetch(SyncEntry entry, String revision);
}
