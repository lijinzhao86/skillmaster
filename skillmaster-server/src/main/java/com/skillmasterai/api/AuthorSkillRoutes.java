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

    static final String ONE_VERSION = VERSIONS + "/{version}";

    static final String DIFF = ONE_VERSION + "/diff";

    static final String NAME = ONE_VERSION + "/name";

    static final String NOTE = ONE_VERSION + "/note";

    /** The write names its version in the body, as every POST action on this system does. */
    static final String DISCARD = VERSIONS + "/discard";

    static final String FILES = ONE_VERSION + "/files/{*relpath}";
}
