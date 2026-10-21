package com.skillmasterai.api;

/**
 * The author plane on the API: reads and draft-only writes, none of which changes what a caller
 * gets (ADR 0061).
 *
 * <p>Separated from {@link SkillRoutes} by a path segment rather than by a parameter, because the
 * two planes answer different questions and carry different auth: a caller reaching these routes is
 * asking about drafts and history, which the consumption plane never shows. The actions that
 * <em>do</em> change what callers read — publish, revoke, restore, offline — deliberately have no
 * route here (ADR 0031, ADR 0046).
 */
final class AuthorSkillRoutes {

    private AuthorSkillRoutes() {
    }

    static final String BASE = "/api/v1/author/skills";

    static final String SKILL = "/{namespace}/{name}";

    static final String VERSIONS = SKILL + "/versions";

    /**
     * The base a first write would start from — a reference (pin and digest), never the content.
     *
     * <p>Its own read because the rule that picks the base is the server's, and a client that
     * copied it would be a second authority for "which draft is the newest".
     */
    static final String DRAFT_BASE = SKILL + "/draft-base";

    static final String ONE_VERSION = VERSIONS + "/{version}";

    static final String DIFF = ONE_VERSION + "/diff";

    static final String NAME = ONE_VERSION + "/name";

    static final String NOTE = ONE_VERSION + "/note";

    /** The write names its version in the body, as every POST action on this system does. */
    static final String DISCARD = VERSIONS + "/discard";

    static final String FILES = ONE_VERSION + "/files/{*relpath}";

    /** A whole version as a zip: the live one by bare address, or any readable one by pin. */
    static final String ARCHIVE = SKILL + "/archive";

    static final String VERSION_ARCHIVE = ONE_VERSION + "/archive";
}
