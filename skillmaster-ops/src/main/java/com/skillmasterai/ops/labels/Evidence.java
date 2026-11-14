package com.skillmasterai.ops.labels;

/**
 * Why a value was claimed: one short quote and where it lives.
 *
 * <p>{@code line = 0} means the evidence is the file's presence; {@code quote} is then the relpath.
 * Anything else is a 1-based line of {@code SKILL.md}, and {@code quote} is that line stripped.
 */
public record Evidence(String kind, String file, int line, String quote) {

    public static final String FRONTMATTER = "frontmatter";
    public static final String REQUIREMENT = "requirement";
    public static final String COMMAND = "command";
    public static final String FILE = "file";
    public static final String FENCE = "fence";

    public Evidence {
        if (kind == null || file == null || quote == null) {
            throw new IllegalArgumentException("evidence needs kind, file and quote");
        }
    }
}
