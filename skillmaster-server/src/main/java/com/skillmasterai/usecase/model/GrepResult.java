package com.skillmasterai.usecase.model;

import java.util.List;

/**
 * What {@code skill grep} receives: only the matches, never the files they came from.
 *
 * @param digest    the version's digest, {@code sha256:}-prefixed on the wire
 * @param pin       how this row is addressed: its version code, or the version number
 * @param hits      matching lines and the context lines the request asked for, in manifest order
 *                  then line order; {@code match} says which is which
 * @param truncated whether the server stopped early because the result was too large; the client
 *                  says so on stderr, because a truncated hit list that looks complete is a wrong
 *                  answer (v7 TD §grep)
 */
public record GrepResult(String digest, String pin, List<Hit> hits, boolean truncated) {

    /**
     * @param match whether this line matched, or is one of the context lines a request asked for
     *              ({@code -A/-B/-C}); the client renders the two differently, the way grep does
     */
    public record Hit(String path, int line, String text, boolean match) {
    }
}
