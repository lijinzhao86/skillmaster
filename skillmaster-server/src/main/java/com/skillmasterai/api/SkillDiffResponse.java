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
     * A strong validator for this answer: the digest of the bytes about to be sent.
     *
     * <p><strong>Over the answer, not over the address.</strong> A published version never changes,
     * so hashing the pair of addresses would have been enough for one — but a draft is edited in
     * place and keeps its address (ADR 0063: a draft <em>is</em> its row id). Hashed that way, the
     * validator outlived the answer it described: after a write the server still computed the same
     * ETag and answered {@code If-None-Match} with 304 and no body, so the client kept showing the
     * file count from before the edit.
     *
     * <p><strong>The serialized body, not a list of fields.</strong> A validator written as a
     * hand-kept list of the record's fields is correct only until somebody adds one and forgets this
     * method — the same way the address-keyed cache was correct only while versions were immutable.
     * Hashing the body the serializer produces cannot drift from it, a field added later enters by
     * itself, and file content cannot forge a field boundary because there are no boundaries to
     * forge. That is what RFC 9110 asks of a strong validator.
     *
     * <p>The cost is hashing a response that has already been computed, bounded by
     * {@link com.skillmasterai.modules.distribution.DiffLimits}; the line diff is the expensive part
     * and it is not what this replaces.
     *
     * @param serialized exactly the bytes the response will carry
     * @return bare lower-case hex; the controller lets Spring quote it
     */
    public static String etag(byte[] serialized) {
        return com.skillmasterai.common.Sha256Hex.of(serialized);
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
