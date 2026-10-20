package com.skillmasterai.modules.distribution;

/**
 * A single-file diff named a relpath that is not part of this comparison.
 *
 * <p>404 rather than serving an empty hunk: the request named a file, and "this comparison has no
 * such file" is the honest answer. Distinct from a path that is in neither version — that one is
 * simply not a changed file, which is the same answer.
 */
public class FileNotInVersionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FileNotInVersionException(String relpath) {
        super("this comparison has no changed file at '" + relpath + "'");
    }
}
