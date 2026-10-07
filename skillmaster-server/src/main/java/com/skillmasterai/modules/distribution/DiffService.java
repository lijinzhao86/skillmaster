package com.skillmasterai.modules.distribution;

import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.distribution.internal.LineDiff;
import com.skillmasterai.modules.version.Manifest;
import com.skillmasterai.modules.version.ManifestEntry;
import com.skillmasterai.modules.version.SkillSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
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
 */
public final class DiffService {

    /**
     * Unchanged lines kept around each change. Three is unified diff's classic value, and it is what
     * a review page shows; it is a constant rather than configuration because it changes nothing
     * about cost — the cap that matters is {@link DiffLimits#maxLines} — and a diff with a different
     * amount of context is not a different diff, only a differently abbreviated one.
     */
    private static final int CONTEXT = 3;

    private final BlobStore blobs;
    private final DiffLimits limits;

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
        Map<String, ManifestEntry> before = base.map(DiffService::byRelpath).orElseGet(Map::of);
        Map<String, ManifestEntry> after = byRelpath(target);

        // Union, sorted. Sorted because the caps below stop at a boundary, and a boundary that lands
        // somewhere different on each run is a page whose content depends on hash order — the same
        // comparison showing a different subset of files every time it is opened.
        Set<String> relpaths = new TreeSet<>(before.keySet());
        relpaths.addAll(after.keySet());

        List<SkillDiff.File> files = new ArrayList<>();
        int rendered = 0;
        int budget = limits.maxLines();
        boolean truncated = false;

        for (String relpath : relpaths) {
            ManifestEntry from = before.get(relpath);
            ManifestEntry to = after.get(relpath);
            if (from != null && to != null && from.blobSha256().equals(to.blobSha256())) {
                // Same content on both sides. Not part of the answer: this lists what differs.
                continue;
            }
            SkillDiff.Status status = from == null ? SkillDiff.Status.ADDED
                    : to == null ? SkillDiff.Status.REMOVED : SkillDiff.Status.MODIFIED;
            ManifestEntry present = to != null ? to : from;

            if (present.isBinary()) {
                // M5 already decided this is not text. Diffing it would produce a hunk per byte that
                // happens to look like a line, which is noise dressed as information.
                //
                // **Before the file cap, deliberately.** A binary is listed the same way whether the
                // budget is spent or not, and this branch does not increment `rendered` — so deciding
                // it here cannot cost a text file its place. Deciding it the other way round made a
                // binary that merely *sorted* past the boundary set `truncated`, claiming the response
                // was cut when the only file left had no lines to cut.
                files.add(new SkillDiff.File(relpath, status, true, null, null, null));
                continue;
            }
            if (rendered >= limits.maxFiles()) {
                truncated = true;
                files.add(new SkillDiff.File(relpath, status, false, null, null, null));
                continue;
            }
            if (sizeOf(from, to) > limits.maxFileBytes()) {
                // Over the per-file cap, so it is listed and not rendered — and that **is** the
                // response being cut short, which is what `truncated` reports. Unlike a binary, where
                // nothing was cut: it simply has no lines to compare.
                truncated = true;
                files.add(new SkillDiff.File(relpath, status, false, null, null, null));
                continue;
            }

            List<SkillDiff.Hunk> hunks = LineDiff.hunksOf(relpath,
                    linesOf(from), linesOf(to), CONTEXT);
            int lines = hunks.stream().mapToInt(hunk -> hunk.lines().size()).sum();
            if (lines > budget) {
                // Computed, and deliberately not sent: the file is over what this response may carry.
                // Its counts go with its hunks — see SkillDiff.File, where null means "not rendered"
                // and zero would claim nothing changed.
                truncated = true;
                files.add(new SkillDiff.File(relpath, status, false, null, null, null));
                continue;
            }

            budget -= lines;
            rendered++;
            files.add(new SkillDiff.File(relpath, status, false, count(hunks, '+'),
                    count(hunks, '-'), hunks));
        }

        return new SkillDiff(base.map(SkillSnapshot::addressSuffix).orElse(null),
                target.addressSuffix(), files, truncated);
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
}
