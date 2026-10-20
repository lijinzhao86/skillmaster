package com.skillmasterai.common;

/**
 * A version resolved and its manifest has no such path — 404 {@code file_not_found}.
 *
 * <p>The same answer the read routes give an exact-match miss, raised here by the write path: a
 * {@code delete}, {@code move} or {@code copy} named a file this version does not have. It is not
 * {@code skill_not_found}: the skill and the version both exist and the caller can read them, so
 * folding this into §4.1's one 404 would tell a client to check an address that is right.
 */
public class VersionFileNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public VersionFileNotFoundException(String relpath) {
        super("this version has no file at '" + relpath + "'");
    }
}
