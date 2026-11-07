package com.skillmasterai.usecase.sync;

/** One upstream file, relative to the skill directory. */
public record FetchedFile(String relpath, byte[] content, boolean executable) {

    /** Most callers only have bytes; a file is not executable unless the source says so. */
    public FetchedFile(String relpath, byte[] content) {
        this(relpath, content, false);
    }
}
