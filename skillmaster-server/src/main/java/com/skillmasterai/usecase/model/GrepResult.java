package com.skillmasterai.usecase.model;

import java.util.List;

/**
 * What {@code skill grep} receives: only the matches, never the files they came from.
 *
 * @param digest    the version's digest, {@code sha256:}-prefixed on the wire
 * @param pin       how this row is addressed: its version code, or the version number
 * @param hits      one entry per matching line, in manifest order then line order
 * @param truncated whether the server stopped early because the result was too large; the client
 *                  says so on stderr, because a truncated hit list that looks complete is a wrong
 *                  answer (v7 TD §grep)
 */
public record GrepResult(String digest, String pin, List<Hit> hits, boolean truncated) {

    public record Hit(String path, int line, String text) {
    }
}
