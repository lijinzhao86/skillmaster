package com.skillmasterai.modules.version;

/**
 * The caller may read this skill but may not do this to it (ADR 0034).
 *
 * <p><strong>The first thing in this system that answers 403 rather than 404, and the reason it is
 * not a 404 is that a 404 would be false.</strong> Everything else that resolves to nothing is
 * rendered as one indistinguishable 404, because a distinction confirms the skill exists. Here the
 * caller has already been told it exists: it is in the listing they just read, or they just fetched
 * its body. Answering "no skill at that address" would send them to check the address, when the
 * address is right and the problem is their standing.
 *
 * <p>Nothing is disclosed by saying so, which is the whole of why this is allowed to exist: the
 * caller's ability to read the skill is what establishes that it is there.
 *
 * <p>Carries no code of its own on the wire — it is {@code 403 forbidden}, the same code CSRF
 * failures use. A distinct code would add a contract surface without hiding anything, since the
 * response says nothing a reader of the skill did not already know. It deliberately carries no
 * {@code WWW-Authenticate} challenge: the caller's token has the scope it needs, and telling it to
 * go and authorize {@code skills:write} again would send it to fetch a permission that is not the
 * missing one.
 */
public class NotPermittedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotPermittedException(String message) {
        super(message);
    }
}
