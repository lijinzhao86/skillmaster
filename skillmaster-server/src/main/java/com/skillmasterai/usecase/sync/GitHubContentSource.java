package com.skillmasterai.usecase.sync;

import com.skillmasterai.modules.skill.GitHubContentReader;
import com.skillmasterai.modules.skill.GitHubSourceAdapter;
import com.skillmasterai.modules.skill.SourceAdapter.SourceBinding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * One archive per repository for the whole run (ADR 0163): the first entry of a repository
     * downloads it, every later entry reads its own directory out of the same file, and a failure
     * is cached too so an unreachable repository fails once rather than once per skill.
     *
     * <p>Entries of one repository are prepared in parallel, so this is filled under
     * {@code computeIfAbsent}; a run that holds no entry of a repository never downloads it.
     */
    private final Map<String, Archive> archives = new ConcurrentHashMap<>();

    private record Archive(Path file, String ref, String error) {
    }

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
    public SignalsResult signals(SyncEntry entry) {
        return adapter.signals(adapter.parse(entry.locator(), entry.path()))
                .<SignalsResult>map(SignalsOk::new)
                .orElseGet(() -> new FetchFailed("signals_unavailable"));
    }

    @Override
    public void beginRun() {
        /* Anything left here is from a run in this process that ended without its finally (an
           Error, a thread that died): delete it before starting a new one. A killed process leaves
           nothing to find — its files are the system temporary directory's to clean. */
        discard();
    }

    @Override
    public void endRun() {
        discard();
    }

    /**
     * Deletes what the run downloaded, by taking each entry out of the map before deleting it — so a
     * worker that gets as far as inserting after this pass started leaves its file in the map for
     * the second pass rather than having it dropped with the whole map.
     */
    private void discard() {
        for (int pass = 0; pass < 2 && !archives.isEmpty(); pass++) {
            for (String key : java.util.List.copyOf(archives.keySet())) {
                Archive archive = archives.remove(key);
                if (archive != null && archive.file() != null) {
                    try {
                        Files.deleteIfExists(archive.file());
                    } catch (IOException ignored) {
                        // The file is in the system temporary directory; failing the run over a
                        // housekeeping detail would trade a real result for nothing.
                    }
                }
            }
        }
    }

    @Override
    public FetchResult fetch(SyncEntry entry, String revision) {
        /* GitHub treats owner/repo case-insensitively, so the cache key must too: two spellings of
           one repository would otherwise download it twice under the promise of once. */
        String key = entry.locator() == null ? "" : entry.locator().trim().toLowerCase(Locale.ROOT);
        Archive archive = archives.computeIfAbsent(key, ignored -> {
            GitHubContentReader.Downloaded downloaded = reader.download(entry.locator());
            return new Archive(downloaded.file(), downloaded.ref(), downloaded.error());
        });
        if (archive.error() != null) {
            return new FetchFailed(archive.error());
        }
        GitHubContentReader.Fetched fetched = reader.filesIn(archive.file(), entry.locator(),
                entry.path(), entry.licensePath(), archive.ref());
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
