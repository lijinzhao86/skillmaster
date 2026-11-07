package com.skillmasterai.usecase.sync;

import com.skillmasterai.modules.skill.GitHubContentReader;
import com.skillmasterai.modules.skill.GitHubSourceAdapter;
import com.skillmasterai.modules.skill.SourceAdapter.SourceBinding;
import tools.jackson.databind.ObjectMapper;

/**
 * GitHub as the first pipe through the source-neutral pipeline (ADR 0157).
 *
 * <p>It translates the reader's facts into the pipeline's vocabulary and nothing else: no frontmatter
 * parsing, no license decision, no zip. Those belong to the base, so a second platform reaches the
 * same behavior without re-implementing them.
 */
public final class GitHubContentSource implements ContentSource {

    public static final String KIND = GitHubSourceAdapter.KIND;

    private final GitHubContentReader reader;
    private final GitHubSourceAdapter adapter;

    public GitHubContentSource(GitHubContentReader reader, ObjectMapper json) {
        this.reader = reader;
        this.adapter = new GitHubSourceAdapter(json);
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean supportsChildren() {
        return true;
    }

    @Override
    public SourceBinding binding(SyncEntry entry) {
        return adapter.parse(entry.locator(), entry.path());
    }

    @Override
    public ChildrenResult children(SyncEntry entry) {
        GitHubContentReader.Children children = reader.children(entry.locator(), entry.path());
        return children.error() == null
                ? new ChildrenOk(children.names())
                : new FetchFailed(children.error());
    }

    @Override
    public RevisionResult revision(SyncEntry entry) {
        GitHubContentReader.Revision revision = reader.revision(entry.locator(), entry.path());
        return revision.error() == null
                ? new RevisionOk(revision.sha())
                : new FetchFailed(revision.error());
    }

    @Override
    public FetchResult fetch(SyncEntry entry, String revision) {
        GitHubContentReader.Fetched fetched = reader.fetch(entry.locator(), entry.path(),
                revision, entry.licensePath());
        if (fetched.error() != null) {
            return new FetchFailed(fetched.error());
        }
        return new Fetched(
                fetched.files().stream()
                        .map(file -> new FetchedFile(file.relpath(), file.content(),
                                file.executable()))
                        .toList(),
                revision,
                fetched.license() == null ? null : new LicenseFile(
                        fetched.license().spdx(), fetched.license().name(),
                        fetched.license().text(), fetched.license().path(),
                        fetched.license().url()));
    }
}
