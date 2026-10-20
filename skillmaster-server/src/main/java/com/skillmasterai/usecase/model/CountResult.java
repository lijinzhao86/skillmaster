package com.skillmasterai.usecase.model;

import java.util.List;

/**
 * What {@code skill wc} receives: counts only, never bytes.
 *
 * <p>The three numbers are always present because the response has one shape — a client that asked
 * for one column still gets an object it can read. {@code lines} counts {@code \n}; {@code words}
 * follows {@link Character#isWhitespace} for text and ASCII whitespace for binary content; a binary
 * file is counted, not skipped, because that is what the local {@code wc} does (v7 TD §count 的响应).
 */
public record CountResult(String digest, String pin, List<FileCount> files, Totals total) {

    public record FileCount(String path, long lines, long words, long bytes) {
    }

    public record Totals(long lines, long words, long bytes) {
    }
}
