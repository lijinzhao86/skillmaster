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
