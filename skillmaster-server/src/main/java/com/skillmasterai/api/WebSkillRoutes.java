package com.skillmasterai.api;

/**
 * §4.1's browser plane for a skill: the paths, in one place because the controller maps them and the
 * security configuration has to cover them.
 *
 * <p>Mirrors {@link SkillRoutes}, which is the same subject on the consumption plane. That there are
 * two is the design and not an accident of naming: since ADR 0031 the planes answer different
 * questions about the same rows — the API plane serves what has been published, the browser plane
 * serves the author's own drafts as well — and a route appearing on both would be a route where one
 * of them answered the other's question.
 *
 * <p><strong>That second reader cannot use these constants.</strong> The architecture rule forbids
 * {@code config} from depending on {@code api}, so {@code SecurityConfig} matches
 * {@code /web/skills/**} by prefix instead. Moving these paths anywhere else under {@code /web/}
 * therefore needs no second edit; moving them out from under it does.
 */
final class WebSkillRoutes {

    private WebSkillRoutes() {
    }

    static final String BASE = "/web/skills";

    /** One skill, by §4.1's address for it. A version suffix is parsed by the reads, not here. */
    static final String SKILL = "/{namespace}/{name}";

    /** The original {@code SKILL.md}, from whichever version the address named. */
    static final String BODY = SKILL + "/body";

    /** One file of that version, by exact relpath. The browser's file list opens these. */
    static final String FILE = SKILL + "/files/{*relpath}";

    /** Two of the skill's versions, side by side. Never published content, so never on the API. */
    static final String DIFF = SKILL + "/diff";

    /**
     * Make a version live. The only endpoint in the system that does, and deliberately on this plane:
     * it carries a session cookie and a CSRF token, which is what "a person meant to do this" is
     * spelled as here (ADR 0031).
     */
    static final String PUBLISH = SKILL + "/publish";

    /** Throw a draft away. One-way, and only ever a draft. */
    static final String DISCARD = SKILL + "/discard";

    /** A new draft, created from a base version plus its first edit (v8). */
    static final String VERSIONS = SKILL + "/versions";

    /** One edit to an existing draft: write, delete or rename (v8). */
    static final String VERSION_EDITS = VERSIONS + "/{version}/edits";

    /** A whole version as a zip, at the address's pin (ADR 0068). */
    static final String ARCHIVE = SKILL + "/archive";

    /** Bring a zip in as the next version of this skill (ADR 0069). */
    static final String IMPORT = VERSIONS + "/import";

    /** Bring a zip into the caller's own namespace; a new name creates the skill. */
    static final String IMPORT_INTO_NAMESPACE = "/import";

    /** A draft's name and note, replaced while it is still a draft (ADR 0055). */
    static final String VERSION_NAME = SKILL + "/versions/{version}/name";
    static final String VERSION_NOTE = SKILL + "/versions/{version}/note";

    /**
     * The release type a draft declares (ADR 0091). A sub-resource of the version, like its name
     * and its note, and for the same reason: the pin is in the path, not in the body.
     */
    static final String VERSION_RELEASE_TYPE = SKILL + "/versions/{version}/release-type";

    /** Take a published, non-current version down, and put it back (ADR 0058). */
    static final String VERSION_REVOKE = SKILL + "/versions/{version}/revoke";
    static final String VERSION_RESTORE = SKILL + "/versions/{version}/restore";

    /** Stop serving the whole skill, and resume (v6). Owner only. */
    static final String OFFLINE = SKILL + "/offline";
    static final String ONLINE = SKILL + "/online";

    /**
     * Who a skill is shared with, and changing that (ADR 0034).
     *
     * <p>The same three routes as {@link SkillRoutes#GRANTS}, on this plane and for a reason that is
     * not symmetry: sharing is the owner's, and the owner is who the browser plane knows — a session
     * is a person sitting in front of the page where the skill is listed. The API plane has them too,
     * because the CLI can reach them; neither is the other's copy of a rule, both call the same three
     * use cases.
     */
    static final String GRANTS = SKILL + "/grants";

    /** One share, withdrawn. The handle is in the path because it is what the caller has. */
    static final String ONE_GRANT = GRANTS + "/{handle}";

    /** Private, or public at one of the two lowest rungs (ADR 0048). A {@code PATCH} on its own route. */
    static final String VISIBILITY = SKILL + "/visibility";

    /**
     * Soft delete and restore (ADR 0050). {@code POST} rather than {@code DELETE}: the action is
     * reversible, and the {@code DELETE /api/v1/skills/{ns}/{name}} shape is the one ADR 0046 took
     * away — reusing it here would make it look like it had come back.
     */
    static final String DELETE = SKILL + "/delete";

    static final String RESTORE = SKILL + "/restore";

    /* ---- v14: how a skill is organized ---- */

    /** The three groups of words on the information page. */
    static final String TAGS = SKILL + "/tags";

    static final String PUBLIC_TAGS = TAGS + "/public";

    static final String MY_TAGS = TAGS + "/my";

    /** The library reference; the information page's join/leave. */
    static final String LIBRARY = SKILL + "/library";

    static final String CATEGORY = SKILL + "/category";

    /** Asking for a rung, and the queue the {@code admin} rung answers (ADR 0052). */
    static final String ACCESS_REQUESTS = SKILL + "/access-requests";

    /** The caller's own pending request, withdrawn. */
    static final String MY_ACCESS_REQUEST = ACCESS_REQUESTS + "/me";

    /** One requester's pending request, by handle. */
    static final String ONE_ACCESS_REQUEST = ACCESS_REQUESTS + "/{handle}";

    static final String APPROVE_ACCESS_REQUEST = ONE_ACCESS_REQUEST + "/approve";

    static final String DENY_ACCESS_REQUEST = ONE_ACCESS_REQUEST + "/deny";

    /**
     * The body's address, with the version resolved and written in.
     *
     * <p>The mirror of {@link SkillRoutes#bodyAt}, and here for the same reason: the value is a URL a
     * client is meant to fetch as given, so assembling it means re-deriving the encoding rule — which
     * is where a name with a space in it starts working on one plane and not the other. Both call
     * {@link SkillRoutes#encodeSegment}, so the rule itself exists once.
     */
    static String bodyAt(String namespaceSlug, String name, String suffix) {
        return BASE + "/" + SkillRoutes.encodeSegment(namespaceSlug) + "/"
                + SkillRoutes.encodeSegment(name) + "@" + suffix + "/body";
    }
}
