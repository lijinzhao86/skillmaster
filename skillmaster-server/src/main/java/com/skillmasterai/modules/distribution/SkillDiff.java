package com.skillmasterai.modules.distribution;

import java.util.List;

/**
 * Two versions of one skill, compared file by file and line by line.
 *
 * <p>Shaped like the comparison a person already knows from a review page: the files that changed,
 * each with hunks of context, removals and additions. It deliberately does not resemble a patch —
 * there is nothing to apply here, only something to read.
 *
 * <p>Unchanged files are absent rather than listed with no hunks. That is a decision about what the
 * response means: the list is "what differs between these two versions", so a file whose digest
 * matched on both sides is not part of the answer. A caller that wants the full manifest has the
 * detail endpoint.
 *
 * @param fromNumber the version compared against, or <strong>null when there was nothing to compare
 *                   with</strong> — a skill nothing has been published from yet, which is the
 *                   ordinary state of one that has just been submitted (ADR 0031). Every file is then
 *                   an addition, and that is an answer rather than an error: the person looking at it
 *                   is being shown exactly what publishing would change
 * @param toNumber   the version being compared, which always exists — it is what the address resolved
 * @param truncated  whether {@link DiffLimits} cut something from {@code files}. True means the
 *                   response is a partial view and says so; a page that renders it must not present
 *                   it as the whole difference. Two of the three caps set it — the file count and the
 *                   line budget — and so does the per-file size cap, because that is a file this
 *                   response chose not to show. A <em>binary</em> file does not: nothing was cut
 *                   there, it simply has no lines to compare, and the file entry says so
 */
public record SkillDiff(String from, String to, List<File> files, boolean truncated) {

    public SkillDiff {
        files = List.copyOf(files);
    }

    /**
     * What happened to one file between the two versions.
     *
     * <p>There is no {@code renamed}. Detecting one means guessing at similarity, and this does not
     * guess: a file that moved is a removal and an addition, which is what it is in terms of the
     * bytes each version holds.
     */
    public enum Status {

        /** In the version being compared, not in the one compared against. */
        ADDED("added"),

        /** In the version compared against, gone from this one. */
        REMOVED("removed"),

        /** In both, with different content. */
        MODIFIED("modified");

        private final String wire;

        Status(String wire) {
            this.wire = wire;
        }

        /** Lowercase, as the wire spells it — the same words a review page shows. */
        public String wire() {
            return wire;
        }
    }

    /**
     * One changed file.
     *
     * <p>{@code hunks} distinguishes three states, and the difference is worth keeping straight:
     * {@code null} means nothing was rendered — a binary file, one past {@link DiffLimits}, or one the
     * response's budget ran out before; {@code []} means it was rendered and has no line-level change
     * (both sides differ, but only in ways splitting into lines does not see — a trailing newline,
     * say); a non-empty list is the diff itself.
     *
     * <p>{@code added} and {@code removed} go null exactly when {@code hunks} do, and for the same
     * reason: they are counted from the hunks, so reporting them with nothing rendered would mean
     * running the line algorithm on content that is deliberately not being sent. Null is the honest
     * value there — "not counted" — where zero would claim the file changed by nothing.
     *
     * @param binary  whether M5 recorded this as binary, which decides whether it is line-diffed at
     *                all. A binary file is never {@code hunks == []}: it is {@code null}, always
     * @param added   lines added, or null when nothing was rendered
     * @param removed lines removed, or null in the same case
     */
    public record File(String relpath, Status status, boolean binary, Integer added, Integer removed,
            List<Hunk> hunks) {
    }

    /**
     * A contiguous run of changes, with the context lines around it.
     *
     * @param header the {@code @@ -a,b +c,d @@} line, taken from the unified diff rather than
     *               recomputed: the offsets are the algorithm's own bookkeeping, and deriving them a
     *               second time is a way to disagree with the lines underneath
     * @param lines  the body, each line prefixed by a space (context), {@code -} (removed) or
     *               {@code +} (added). The prefixes are unified diff's and are kept as-is: they carry
     *               the same information as a colour would, and a client that renders plain text
     *               still shows a correct diff
     */
    public record Hunk(String header, List<String> lines) {

        public Hunk {
            lines = List.copyOf(lines);
        }
    }
}
