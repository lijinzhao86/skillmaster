package com.skillmasterai.modules.version;

/**
 * One file of a skill version: where it sits, and which blob holds its bytes.
 *
 * @param relpath     path relative to the skill root, POSIX separators
 * @param blobSha256  lowercase hex digest of the bytes, the address in {@code blob}
 * @param size        byte count
 * @param isBinary    whether the bytes are not valid UTF-8; a hint for clients, not a rule
 * @param mode        unix permission bits, derived from {@code relpath} (ADR 0166); **not part of
 *                    the digest** — see {@link Manifest#digest()}
 */
public record ManifestEntry(String relpath, String blobSha256, long size, boolean isBinary,
        int mode) {

    /**
     * For callers that only move bytes around. The mode follows the path (v51): a file's permission
     * bits are a fact about where it sits, so a caller that does not know them cannot get them wrong.
     */
    public ManifestEntry(String relpath, String blobSha256, long size, boolean isBinary) {
        this(relpath, blobSha256, size, isBinary, ExecutableMode.of(relpath));
    }
}
