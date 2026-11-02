package com.skillmasterai.modules.ingest;

/**
 * The ceilings an upload is held to: <strong>512 files, 100 MiB per file, 512 MiB per version
 * (SKILL.md included)</strong>. Versions of the same skill are not summed.
 *
 * <p>v39 raised the byte ceilings to make room for documents and media (ADR 0130/0131). The file
 * count stays at the Agent Skills extension's own number; the byte ceilings are now this platform's
 * choice, not the extension's, and a package that exceeds them is refused here rather than by the
 * container.
 *
 * @param maxFiles      file count ceiling
 * @param maxTotalBytes ceiling on the sum of file bytes, uncompressed
 * @param maxFileBytes  ceiling on one file's bytes, uncompressed
 */
public record IngestLimits(int maxFiles, long maxTotalBytes, long maxFileBytes) {

    public static final int STANDARD_MAX_FILES = 512;
    public static final long STANDARD_MAX_FILE_BYTES = 100L * 1024 * 1024;
    public static final long STANDARD_MAX_BYTES = 512L * 1024 * 1024;

    /**
     * The largest text file {@code grep} scans and {@code put} canonicalises (ADR 0130, ADR 0133).
     *
     * <p>{@code wc} counts by streaming and is deliberately not capped (ADR 0133). The number is
     * one place so "can write but cannot scan" cannot grow a second boundary.
     */
    public static final long STANDARD_TEXT_MAX_BYTES = 16L * 1024 * 1024;

    /**
     * The largest package the zip import path still reads into memory, <strong>after
     * decompression</strong>.
     *
     * <p><strong>An interim guard, not a product limit.</strong> The single-file routes stream and
     * accept the full 512 MiB; the zip import still builds every entry as a byte array, so a
     * package whose <em>uncompressed</em> content reaches the product ceiling could exhaust the
     * heap — a highly compressible archive passes any check on the zip's own size. Until that path
     * is streamed (v39 P3), content above this is refused with a sentence that names the way
     * forward — upload the files individually — rather than failing as an out-of-memory error.
     */
    public static final long PACKAGE_IN_MEMORY_MAX_BYTES = 128L * 1024 * 1024;

    public static final IngestLimits STANDARD =
            new IngestLimits(STANDARD_MAX_FILES, STANDARD_MAX_BYTES, STANDARD_MAX_FILE_BYTES);

    /** For callers that only care about the two outer ceilings; one file may be the whole total. */
    public IngestLimits(int maxFiles, long maxTotalBytes) {
        this(maxFiles, maxTotalBytes, maxTotalBytes);
    }

    public IngestLimits {
        if (maxFiles < 1) {
            throw new IllegalArgumentException("maxFiles must be positive, was " + maxFiles);
        }
        if (maxTotalBytes < 1) {
            throw new IllegalArgumentException("maxTotalBytes must be positive, was " + maxTotalBytes);
        }
        if (maxFileBytes < 1) {
            throw new IllegalArgumentException("maxFileBytes must be positive, was " + maxFileBytes);
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
        return new IngestLimits(maxFiles + 1, maxTotalBytes + SkillSidecar.MAX_BYTES, maxFileBytes);
    }

    /** The same ceiling in the unit the product's copy uses: {@code 100 MiB}, {@code 1.5 MiB}. */
    public static String mib(long bytes) {
        return com.skillmasterai.common.Bytes.human(bytes);
    }
}
