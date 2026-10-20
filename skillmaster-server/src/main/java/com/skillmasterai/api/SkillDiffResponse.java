package com.skillmasterai.api;

import com.skillmasterai.modules.distribution.SkillDiff;
import java.util.List;

/**
 * The comparison between two versions of one skill (ADR 0031).
 *
 * <p>Written for a page rather than for a client that would apply it, which is what every choice here
 * follows from. There is no {@code index} line and no {@code ---}/{@code +++} pairs: a patch needs
 * those to know which file a hunk belongs to, and here the hunks are already nested inside the file
 * they belong to. What is kept is the body's {@code ' '}/{@code '-'}/{@code '+'} prefixes, because
 * they carry the same information as a colour would — a client that renders plain text still shows a
 * correct diff, and one that colours lines reads the prefix instead of guessing from position.
 *
 * <p>{@code from} is null for a skill nothing has been published from, which is a real answer: the
 * comparison is against nothing, so every file is an addition. The field is echoed rather than
 * inferred by the client because the server is what defaulted it.
 *
 * <p>{@code added} and {@code removed} are null together with {@code hunks}, always — see
 * {@link SkillDiff.File}. A client that needs to show a count must handle null, and it should: the
 * count of a file that was not diffed is not zero, it is unknown, and a page that printed "0 +" for a
 * 2 MB file would be stating something it was never told.
 *
 * @param truncated whether the caps cut something. A page must say so when it is true — silently
 *                  showing part of a diff is the one failure this shape exists to avoid
 */
public record SkillDiffResponse(String from, String to, boolean truncated, String next,
        List<File> files) {

    public record File(String relpath, String status, boolean binary, long size, Integer added,
            Integer removed, List<Hunk> hunks) {
    }

    public record Hunk(String header, List<String> lines) {
    }

    /**
     * A strong validator for this answer.
     *
     * <p>Versions are immutable, so a comparison is a pure function of the two version addresses and
     * the two request parameters that shape it. The digest is over exactly that material: the same
     * request for the same pair returns the same ETag, and a second request can be answered 304
     * without running the line diff at all.
     *
     * @param file   the single-file parameter, or null
     * @param cursor the batch cursor, or null
     * @return bare lower-case hex; the controller lets Spring quote it
     */
    public static String etag(SkillDiffResponse diff, String file, String cursor) {
        String material = (diff.from() == null ? "" : diff.from())
                + '\u0001' + diff.to()
                + '\u0001' + (file == null ? "" : file)
                + '\u0001' + (cursor == null ? "" : cursor);
        return com.skillmasterai.common.Sha256Hex.of(
                material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Whether an {@code If-None-Match} header names this validator (weak prefixes allowed). */
    public static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String bare = candidate.trim().replace("W/", "").replace("\"", "");
            if (bare.equals(etag)) {
                return true;
            }
        }
        return false;
    }

    public static SkillDiffResponse of(SkillDiff diff) {
        return new SkillDiffResponse(
                diff.from(),
                diff.to(),
                diff.truncated(),
                diff.next(),
                diff.files().stream()
                        .map(file -> new File(file.relpath(), file.status().wire(), file.binary(),
                                file.size(), file.added(), file.removed(),
                                file.hunks() == null ? null : file.hunks().stream()
                                        .map(hunk -> new Hunk(hunk.header(), hunk.lines()))
                                        .toList()))
                        .toList());
    }
}
