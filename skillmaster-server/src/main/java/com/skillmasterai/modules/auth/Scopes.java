package com.skillmasterai.modules.auth;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The scopes this API understands, and the one place that decides which of them a request needs.
 *
 * <p>That last part is the reason this class exists rather than two constants. The 403 challenge
 * has to name the scope that was missing ({@code WWW-Authenticate: Bearer error="insufficient_scope",
 * scope="..."}), and the authorization rule has to require the same one. Computing it in two
 * places is how a challenge ends up advertising a scope the server does not actually check.
 *
 * <p>Since v2 it also holds the one rule about how the scopes relate to each other — see
 * {@link #withImpliedRead}. A rule about the vocabulary belongs where the vocabulary is.
 */
public final class Scopes {

    private Scopes() {
    }

    public static final String SKILLS_READ = "skills:read";
    public static final String SKILLS_WRITE = "skills:write";

    /** The whole vocabulary, so a caller can ask what exists rather than hardcode the pair. */
    public static final Set<String> ALL = Set.of(SKILLS_READ, SKILLS_WRITE);

    /**
     * Whether this is a scope this API understands. Anything else is a client bug, not a mistake.
     *
     * <p><strong>Null is not understood either, and asking must not throw.</strong> {@code ALL} is an
     * immutable set, whose {@code contains} refuses a null probe by design — so a request that carried
     * {@code "scopes": ["skills:read", null]} turned a question about a malformed body into a
     * {@link NullPointerException} and answered 500 for a request this class has a 400 for.
     */
    public static boolean isKnown(String scope) {
        return scope != null && ALL.contains(scope);
    }

    /**
     * Folds in what the requested scopes imply: asking for write gets read as well (ADR 0039
     * §决定 2).
     *
     * <p><strong>Because "may write but not read" is not a combination anybody means.</strong> The
     * reference product has an explicit ability map for the same reason, and a key with only
     * {@code skills:write} could not run so much as a listing — a credential that refuses to do the
     * thing its own name suggests. The mistake would be in the vocabulary, and the vocabulary is
     * here, so the correction is too.
     *
     * <p>Applied once, when a key is issued, so the row is the truth: nothing downstream has to know
     * the rule, and nothing can disagree with the row. Read is never dropped — this only ever adds.
     */
    public static Set<String> withImpliedRead(Set<String> requested) {
        if (!requested.contains(SKILLS_WRITE)) {
            return Set.copyOf(requested);
        }
        Set<String> complete = new LinkedHashSet<>(requested);
        complete.add(SKILLS_READ);
        return Set.copyOf(complete);
    }

    /** Spring Security represents a granted scope as an authority prefixed {@code SCOPE_}. */
    public static String authority(String scope) {
        return "SCOPE_" + scope;
    }

    /**
     * Takes the method as a string rather than an {@code HttpMethod}: the access-denied handler
     * runs on arbitrary requests, and parsing an unrecognised verb into the enum would throw
     * while we are trying to produce an error response.
     */
    public static String requiredForMethod(String method) {
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "GET", "HEAD", "OPTIONS" -> SKILLS_READ;
            default -> SKILLS_WRITE;
        };
    }
}
