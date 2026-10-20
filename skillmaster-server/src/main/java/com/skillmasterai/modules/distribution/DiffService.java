package com.skillmasterai.modules.distribution;

import com.skillmasterai.common.ErrorCode;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.distribution.internal.LineDiff;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.SkillSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * M9: two versions of a skill, compared.
 *
 * <p>Takes two <strong>already resolved</strong> snapshots rather than an address and a pair of
 * version numbers. Which versions a caller is entitled to compare is M7's question and the caller's
 * policy — the author's page may compare drafts, nothing else may compare at all — and pushing that
 * decision in here would put two planes' rules behind one method. So this class does one thing:
 * given two versions that exist, say what differs.
 *
 * <p>It owns no tables, like the rest of M9: the manifests come from M7's snapshots and the bytes
 * from M6 by digest. Nothing here is fetched by path — the same rule that makes §4.2's exact-match
 * lookup unfalsifiable applies to reading a manifest entry's content, and a diff is no exception.
 *
 * <p>Three caps bound the work rather than the answer: see {@link DiffLimits}. What they cut is
 * reported — a file left unrendered says so on the file, and a response that cut something says so at
 * the top — because a comparison that quietly shows half the change is worse than one that refuses.
 *
 * <p><strong>List first, bodies in batches (v6).</strong> Past {@link DiffLimits#maxFiles} the first
 * response carries every changed file's status, size and numstat and no bodies; its {@code next} is
 * the cursor for batch zero. The numstat pass is not limited by the batch size — a file after the
 * twentieth still gets its {@code +n −m} — because "how much changed" is the question the list
 * answers. Versions are immutable, so the answers for a pair are kept in a small bounded LRU: opening
 * the same comparison twice does not run the line algorithm twice.
 */
public final class DiffService {

    /**
     * Unchanged lines kept around each change. Three is unified diff's classic value, and it is what
     * a review page shows; it is a constant rather than configuration because it changes nothing
     * about cost — the cap that matters is {@link DiffLimits#maxLines} — and a diff with a different
     * amount of context is not a different diff, only a differently abbreviated one.
     */
    private static final int CONTEXT = 3;

    /** How many comparisons are remembered. Bounded by count, not bytes; entries are small pages. */
    private static final int CACHE_ENTRIES = 32;

    private final BlobStore blobs;
    private final DiffLimits limits;

    /**
     * The recalculated answers, least recently used first to be evicted.
     *
     * <p>Access order rather than insertion order: the page someone keeps coming back to stays. The
     * key contains both version addresses and the two parameters that change the response, because
     * a batch and a single-file view are different answers to the same pair.
     */
    private final Map<String, SkillDiff> recent = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SkillDiff> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

    public DiffService(BlobStore blobs, DiffLimits limits) {
        this.blobs = blobs;
        this.limits = limits;
    }

    /**
     * What changed between two versions of one skill.
     *
     * @param base   the version to compare against, or empty when there is nothing to compare with.
     *               Empty is not an error and not a miss: it is a skill nothing has been published
     *               from (ADR 0031), and the honest answer is that every file of {@code target} is
     *               new. The caller decides whether that state is one it wants to answer at all
     * @param target the version being compared. Must be the same skill as {@code base}
     */
    public SkillDiff compare(Optional<SkillSnapshot> base, SkillSnapshot target) {
        return compare(base, target, null, null);
    }

    /**
     * The same comparison, with the list-first contract of v6.
     *
     * <p>Every file's {@code size} and numstat arrive with the list; only the hunks are batched. That
     * is the split the page needs: "how many lines changed" is the first question a reader asks and
     * it must be on screen with the file tree, while the bodies can follow.
     *
     * @param onlyFile one relpath whose hunks to render, or null for the batch
     * @param cursor   an opaque offset into the changed-file list from a previous response
     */
    public SkillDiff compare(Optional<SkillSnapshot> base, SkillSnapshot target, String onlyFile,
            String cursor) {
        String cacheKey = cacheKey(base, target, onlyFile, cursor);
        SkillDiff remembered = cached(cacheKey);
        if (remembered != null) {
            return remembered;
        }

        Map<String, ManifestEntry> before = base.map(DiffService::byRelpath).orElseGet(Map::of);
        Map<String, ManifestEntry> after = byRelpath(target);

        Set<String> relpaths = new TreeSet<>(before.keySet());
        relpaths.addAll(after.keySet());

        List<ChangedFile> changed = new ArrayList<>();
        for (String relpath : relpaths) {
            ManifestEntry from = before.get(relpath);
            ManifestEntry to = after.get(relpath);
            if (from != null && to != null && from.blobSha256().equals(to.blobSha256())) {
                continue;
            }
            SkillDiff.Status status = from == null ? SkillDiff.Status.ADDED
                    : to == null ? SkillDiff.Status.REMOVED : SkillDiff.Status.MODIFIED;
            ManifestEntry present = to != null ? to : from;
            changed.add(new ChangedFile(relpath, status, present, sizeOf(from, to)));
        }

        if (onlyFile != null) {
            Optional<ChangedFile> one = changed.stream()
                    .filter(file -> file.relpath().equals(onlyFile)).findFirst();
            if (one.isEmpty()) {
                throw new FileNotInVersionException(onlyFile);
            }
            ChangedFile file = one.get();
            List<SkillDiff.Hunk> hunks = renderable(file) ? hunksOf(file, before, after) : null;
            // **The same per-file line cap as the batch** (TD §改动: a file whose hunks exceed 5,000
            // lines does not enter the diff). `file=` is the only way the page fetches a body, so a
            // cap that lived only in the batch path was no cap at all: the single-file request
            // rendered the whole thing.
            if (hunks != null && hunkLines(hunks) > limits.maxLines()) {
                hunks = null;
            }
            SkillDiff answer = new SkillDiff(base.map(SkillSnapshot::addressSuffix).orElse(null),
                    target.addressSuffix(),
                    List.of(toFile(file, hunks, hunks != null)),
                    false, null);
            remember(cacheKey, answer);
            return answer;
        }

        // Every file's numstat is computed once, before any batching. It is what rides with the
        // list, and it is also the body the batch will render — one line-diff per file per call,
        // never two. A file whose own hunk body exceeds the response budget is not diffed at all,
        // so it can never block a batch forever (the pre-v6 shape would have made `next` point at
        // the same file on every request).
        List<FileState> states = new ArrayList<>(changed.size());
        boolean cutBySize = false;
        for (ChangedFile file : changed) {
            boolean tooBig = file.size() > limits.maxFileBytes();
            List<SkillDiff.Hunk> hunks = !file.entry().isBinary() && !tooBig
                    ? hunksOf(file, before, after) : null;
            if (hunks != null && hunkLines(hunks) > limits.maxLines()) {
                // GitLab's line: a file this large is listed with its size and no counts rather
                // than making every later batch start at the same place.
                hunks = null;
            }
            cutBySize |= hunks == null && !file.entry().isBinary();
            states.add(new FileState(file, hunks));
        }

        boolean listFirst = changed.size() > limits.maxFiles();
        boolean firstList = listFirst && isBlank(cursor);
        int start = parseCursor(cursor, changed.size());
        boolean renderBodies = !firstList;

        List<SkillDiff.File> files = new ArrayList<>(states.size());
        Integer nextIndex = null;
        int rendered = 0;
        int lines = 0;
        for (int i = 0; i < states.size(); i++) {
            FileState state = states.get(i);
            boolean render = renderBodies && i >= start && state.hunks() != null;
            if (render) {
                int size = hunkLines(state.hunks());
                if (rendered >= limits.maxFiles() || lines + size > limits.maxLines()) {
                    render = false;
                }
            }
            if (render) {
                rendered++;
                lines += hunkLines(state.hunks());
                files.add(toFile(state.file(), state.hunks(), true));
            } else {
                files.add(toFile(state.file(), state.hunks(), false));
                // Only files at or after this batch's start can need a next batch. A file before
                // `start` was rendered (or deliberately withheld) by an earlier response, and
                // pointing back at it is how a cursor loop never ends.
                if (state.hunks() != null && i >= start && nextIndex == null) {
                    nextIndex = i;
                }
            }
        }

        String next = firstList ? "0" : nextIndex == null ? null : String.valueOf(nextIndex);
        SkillDiff answer = new SkillDiff(base.map(SkillSnapshot::addressSuffix).orElse(null),
                target.addressSuffix(), files, next != null || cutBySize, next);
        remember(cacheKey, answer);
        return answer;
    }

    private record ChangedFile(String relpath, SkillDiff.Status status, ManifestEntry entry,
            long size) {
    }

    private record FileState(ChangedFile file, List<SkillDiff.Hunk> hunks) {
    }

    /**
     * Whether this file may be line-diffed: text on both sides, and neither side past the size cap.
     * The cap takes the larger side because either can be the expensive one.
     */
    private boolean renderable(ChangedFile file) {
        return !file.entry().isBinary() && file.size() <= limits.maxFileBytes();
    }

    private List<SkillDiff.Hunk> hunksOf(ChangedFile file, Map<String, ManifestEntry> before,
            Map<String, ManifestEntry> after) {
        return LineDiff.hunksOf(file.relpath(), linesOf(before.get(file.relpath())),
                linesOf(after.get(file.relpath())), CONTEXT);
    }

    private static int hunkLines(List<SkillDiff.Hunk> hunks) {
        return hunks.stream().mapToInt(hunk -> hunk.lines().size()).sum();
    }

    /**
     * One file in the answer.
     *
     * <p><strong>The counts do not depend on whether the body is in this response.</strong> numstat
     * is cheap compared with a hunk body and is the first thing a reader asks; it rides with the
     * list, and only binary or over-size files — where there are no countable lines — report null.
     * The hunks themselves are what a batch withholds.
     *
     * @param hunks    the computed hunks, or null for binary and over-size files
     * @param rendered whether those hunks are part of this response
     */
    private static SkillDiff.File toFile(ChangedFile file, List<SkillDiff.Hunk> hunks,
            boolean rendered) {
        Integer added = hunks == null ? null : count(hunks, '+');
        Integer removed = hunks == null ? null : count(hunks, '-');
        return new SkillDiff.File(file.relpath(), file.status(), file.entry().isBinary(),
                file.entry().size(), added, removed, rendered ? hunks : null);
    }

    /**
     * A snapshot's files, keyed by relpath.
     *
     * <p>No collision handling, because there cannot be one: a manifest is keyed by relpath and M5
     * rejects an upload with two entries for the same path. Duplicate keys would silently drop a file
     * from the comparison, so if one ever appeared this would rather fail — {@link java.util.Map#of}
     * accepts it, but the manifest's own construction is what guarantees uniqueness.
     */
    private static Map<String, ManifestEntry> byRelpath(SkillSnapshot snapshot) {
        Manifest manifest = snapshot.manifest();
        Map<String, ManifestEntry> entries = new HashMap<>();
        for (ManifestEntry entry : manifest.entries()) {
            entries.put(entry.relpath(), entry);
        }
        return entries;
    }

    /**
     * The larger of the two sizes, or the only one there is.
     *
     * <p>Either side can be the expensive one — an addition decodes only the new content, a removal
     * only the old — so the cap has to consider both rather than the one that happens to be the
     * target.
     */
    private static long sizeOf(ManifestEntry from, ManifestEntry to) {
        if (from == null) {
            return to.size();
        }
        if (to == null) {
            return from.size();
        }
        return Math.max(from.size(), to.size());
    }

    /**
     * One side's text, or nothing when it did not exist.
     *
     * <p>A missing side is not read: {@code from == null} means the file was added, so there is no
     * blob to fetch and no digest to fetch it by — and the empty line list is what makes every line
     * of the other side an addition, which is exactly the answer.
     */
    private List<String> linesOf(ManifestEntry entry) {
        if (entry == null) {
            return List.of();
        }
        // UTF-8, and the replacement character for anything that is not. M5 called this file text,
        // and this is the charset §4.2 serves; a file in some other encoding renders imperfectly,
        // which is better than refusing to show the comparison at all.
        return LineDiff.lines(new String(blobs.get(entry.blobSha256()), StandardCharsets.UTF_8));
    }

    /** Hunk body lines carrying this prefix. Headers are not body lines and cannot be counted here. */
    private static int count(List<SkillDiff.Hunk> hunks, char prefix) {
        return (int) hunks.stream()
                .flatMap(hunk -> hunk.lines().stream())
                .filter(line -> !line.isEmpty() && line.charAt(0) == prefix)
                .count();
    }

    /**
     * A cursor this endpoint issued, or a client bug.
     *
     * <p>The cursor is opaque, so a value that is not one is not a page that happens to be empty; it
     * is a request this endpoint cannot answer. Saying so is better than letting
     * {@link NumberFormatException} become a 500.
     */
    private static int parseCursor(String cursor, int size) {
        if (isBlank(cursor)) {
            return 0;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(cursor.trim());
        } catch (NumberFormatException e) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "cursor is not a token this endpoint issued");
        }
        if (parsed < 0) {
            throw new RequestException(ErrorCode.INVALID_REQUEST,
                    "cursor is not a token this endpoint issued");
        }
        return Math.min(parsed, size);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String cacheKey(Optional<SkillSnapshot> base, SkillSnapshot target,
            String onlyFile, String cursor) {
        return base.map(snapshot -> snapshot.addressSuffix() == null ? ""
                        : snapshot.addressSuffix()).orElse("")
                + '\u0001' + (target.addressSuffix() == null ? "" : target.addressSuffix())
                + '\u0001' + (onlyFile == null ? "" : "f:" + onlyFile)
                + '\u0001' + (isBlank(cursor) ? "" : "c:" + cursor.trim());
    }

    private synchronized SkillDiff cached(String key) {
        return recent.get(key);
    }

    private synchronized void remember(String key, SkillDiff diff) {
        recent.put(key, diff);
    }
}
