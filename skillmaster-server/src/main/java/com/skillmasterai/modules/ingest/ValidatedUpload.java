package com.skillmasterai.modules.ingest;

import java.util.List;

/**
 * What M5 hands on: the skill's content, plus the version metadata that travelled beside it, plus
 * what the door changed on the way in.
 *
 * <p><strong>The sidecar is separate from the upload on purpose.</strong> {@link SkillUpload} is
 * exactly what becomes a version — its files, their count and their bytes, and the frontmatter that
 * describes them. The metadata is not part of that: it is a declaration about the <em>row</em> the
 * upload will create, and folding it into the upload would put a field into content identity that
 * must never change it (ADR 0069).
 *
 * <p><strong>What changed is reported, not hidden.</strong> v33 rewrites text on the way in
 * (ADR 0117) and drops archive junk. Both are recorded so the audit can answer "why is this digest
 * different from the bytes I made", and {@code dropped} also travels back to the author — a file
 * that vanished without a word is exactly what §3.3 forbids.
 *
 * @param upload     the content, with any sidecar already removed from its file list
 * @param sidecar    the version metadata, or {@code null} when the package carried none
 * @param normalized every file whose bytes the door changed, with the rules that fired
 * @param dropped    archive artefacts that were never content, in the order they were read
 */
public record ValidatedUpload(
        SkillUpload upload,
        SkillSidecar sidecar,
        List<Normalized> normalized,
        List<String> dropped) {

    public ValidatedUpload {
        normalized = List.copyOf(normalized);
        dropped = List.copyOf(dropped);
    }

    /** One file's rewrite: which path, and which of {@link com.skillmasterai.modules.canon.TextCanon}'s
     *  rules fired on it. */
    public record Normalized(String path, List<String> changes) {
        public Normalized {
            changes = List.copyOf(changes);
        }
    }
}
