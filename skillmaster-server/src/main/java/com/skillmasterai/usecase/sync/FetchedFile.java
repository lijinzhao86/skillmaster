package com.skillmasterai.usecase.sync;

/**
 * One upstream file at its path in the repository.
 *
 * <p>{@code executable} is what the archive said, and nothing decides permissions from it: a file is
 * executable where it lands, which is the relpath (ADR 0166). It stays on the record because it is a
 * fact about the upstream archive; a caller that wants to know whether the platform will execute the
 * file must ask {@code ExecutableMode}.
 */
public record FetchedFile(String relpath, byte[] content, boolean executable) {

    /** Most callers only have bytes. */
    public FetchedFile(String relpath, byte[] content) {
        this(relpath, content, false);
    }
}
