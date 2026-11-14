package com.skillmasterai.ops.labels;

import java.util.List;

/**
 * What a rule may read: the root {@code SKILL.md} and the file manifest.
 *
 * <p><strong>Deliberately not "the files".</strong> Import has every byte; refresh is given only
 * these two facts by the server. A rule that read another file's contents would give one answer on
 * import and another on refresh, so the input is narrowed to what both paths share.
 */
public record SkillFacts(String skillMd, List<FileFact> files) {

    public SkillFacts {
        files = List.copyOf(files);
    }

    /** One entry of the manifest; the bytes are not here. */
    public record FileFact(String relpath, long size, boolean binary) {
    }
}
