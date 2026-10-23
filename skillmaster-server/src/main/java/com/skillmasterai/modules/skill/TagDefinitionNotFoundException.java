package com.skillmasterai.modules.skill;

/**
 * The private-tag definition named by an id does not exist, or belongs to somebody else (v14).
 *
 * <p>One answer for both, for the reason every 404 in this system has one: a distinct answer for
 * "that one is not yours" would confirm that a definition with that id exists.
 */
public final class TagDefinitionNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TagDefinitionNotFoundException() {
        super("no tag definition with that id");
    }
}
