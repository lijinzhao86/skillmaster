package com.skillmasterai.modules.distribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A finished zip on local disk, waiting to be streamed and deleted.
 *
 * <p><strong>Why a temp file rather than memory.</strong> The content ceiling is 16 MiB, which one
 * request could hold comfortably, but concurrent downloads would multiply that on the heap; more
 * importantly, a failure while reading a blob can only become a proper error response if it happens
 * <em>before</em> the first byte is sent. Building the file first makes the error boundary clean.
 *
 * <p><strong>The caller owns the file.</strong> {@link #close()} deletes it, and the controller
 * closes it in the streaming body's {@code finally}; a process that dies mid-flight leaves the file
 * to the operating system's temporary-directory policy.
 *
 * @param path     the zip on disk
 * @param filename what {@code Content-Disposition} should say
 * @param digest   the manifest digest the file was built from, lowercase hex
 * @param size     the file's byte count, for {@code Content-Length}
 */
public record ArchiveArtifact(Path path, String filename, String digest, long size)
        implements AutoCloseable {

    @Override
    public void close() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A file we cannot delete is a disk-space problem, not a response problem; the
            // operating system's temp policy is the backstop (ADR 0068 后果).
        }
    }
}
