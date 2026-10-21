package com.skillmasterai.modules.ingest;

/**
 * The ceilings an upload is held to: <strong>512 files and 16 MiB per skill, SKILL.md included</strong>.
 *
 * <p>Not arbitrary and not ours to raise casually — §1.3 records them as the Agent Skills
 * extension's own limits, which the server <em>should not</em> exceed because clients enforce the
 * same numbers. A server that accepted more would happily store skills no client will load.
 *
 * @param maxFiles      file count ceiling
 * @param maxTotalBytes ceiling on the sum of file bytes, uncompressed
 */
public record IngestLimits(int maxFiles, long maxTotalBytes) {

    public static final int STANDARD_MAX_FILES = 512;
    public static final long STANDARD_MAX_BYTES = 16L * 1024 * 1024;

    public static final IngestLimits STANDARD = new IngestLimits(STANDARD_MAX_FILES, STANDARD_MAX_BYTES);

    public IngestLimits {
        if (maxFiles < 1) {
            throw new IllegalArgumentException("maxFiles must be positive, was " + maxFiles);
        }
        if (maxTotalBytes < 1) {
            throw new IllegalArgumentException("maxTotalBytes must be positive, was " + maxTotalBytes);
        }
    }

    /**
     * The reader's ceiling when the package may carry one sidecar (ADR 0069).
     *
     * <p><strong>The reader gets room; the validator keeps the rule.</strong> The sidecar is not
     * content, so a package with 512 files and a sidecar must get past the reader, and a package
     * with 513 content files must not. The reader cannot tell those apart without learning what the
     * reserved path is — and it is deliberately a general-purpose reader with no opinion about
     * platform names. So it is allowed one extra entry and a small byte allowance, and the
     * validator re-applies the real ceilings to the content it kept.
     *
     * <p>{@link SkillSidecar#MAX_BYTES} is an internal allowance: a valid sidecar is a few hundred
     * bytes, and a package that exceeds this by more is one whose total really is over the limit.
     */
    public IngestLimits withRoomForSidecar() {
        return new IngestLimits(maxFiles + 1, maxTotalBytes + SkillSidecar.MAX_BYTES);
    }
}
