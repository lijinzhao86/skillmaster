package com.skillmasterai.modules.version;

/**
 * One file of a skill version: where it sits, and which blob holds its bytes.
 *
 * @param relpath     path relative to the skill root, POSIX separators
 * @param blobSha256  lowercase hex digest of the bytes, the address in {@code blob}
 * @param size        byte count
 * @param isBinary    whether the bytes are not valid UTF-8; a hint for clients, not a rule
 * @param mode        unix permission bits (0644 when nothing says otherwise); **not part of the
 *                    digest** — see {@link Manifest#digest()}
 */
public record ManifestEntry(String relpath, String blobSha256, long size, boolean isBinary,
        int mode) {

    /** 0644: what a file gets when no path told us anything about it. */
    public static final int DEFAULT_MODE = 0644;

    /**
     * For callers that only move bytes around.
     *
     * <p>The edit and copy paths reach here without having asked about permissions, and inventing
     * a different default for each of them would make "what mode does a file have" depend on which
     * command touched it last. They pass the old entry's mode when they have one; this constructor
     * is the answer when there was nothing to carry.
     */
    public ManifestEntry(String relpath, String blobSha256, long size, boolean isBinary) {
        this(relpath, blobSha256, size, isBinary, DEFAULT_MODE);
    }

    /** Whether the file may be executed. Only the owner-execute bit is consulted, as tar does. */
    public boolean executable() {
        return (mode & 0111) != 0;
    }
}
