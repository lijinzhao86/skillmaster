package com.skillmasterai.modules.ingest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A validated upload, ready to be published. M5's output and M7's input.
 *
 * @param name           the frontmatter {@code name}, also the skill's directory name
 * @param title          the frontmatter {@code title}, falling back to the name
 * @param description    the frontmatter {@code description} — required, and the search hot path
 * @param frontmatter    every frontmatter field as parsed, unknown ones included, to be stored
 *                       verbatim. Dropping unknown fields is how the baseline lost Feishu's
 *                       nested {@code metadata.requires.bins} (§3.3 point 4).
 * @param files          every file of the skill, {@code SKILL.md} included — the manifest of §1.3
 *                       lists SKILL.md alongside the attachments, so it is not special-cased out
 */
public record SkillUpload(
        String name,
        String title,
        String description,
        Map<String, Object> frontmatter,
        List<IngestedFile> files) {

    public SkillUpload {
        // Not Map.copyOf: it refuses null *values*, and a frontmatter key written without one
        // (`license:` with nothing after it) parses to exactly that — ordinary YAML rather than a
        // malformed upload, and it would have failed as an uncaught NullPointerException. The copy
        // is still defensive; it just has to tolerate the null, because storing the field as null
        // is the honest record of what the author wrote.
        frontmatter = Collections.unmodifiableMap(new LinkedHashMap<>(frontmatter));
        files = List.copyOf(files);
    }

    public IngestedFile skillMd() {
        return files.stream()
                .filter(file -> file.relpath().equals(SkillUploadValidator.SKILL_MD))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("a validated upload always has SKILL.md"));
    }
}
