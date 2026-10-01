package com.skillmasterai.api;

import java.nio.charset.StandardCharsets;
import org.springframework.web.util.UriUtils;

/**
 * §4.1's routes for a skill, in one place because two things need them: the controller that serves
 * them, and the manifest that advertises a per-file URI.
 *
 * <p>The reason to keep them together is the one {@code GatewayService} gives for its own routes — a
 * path written down twice is a path that can drift, and this drift is invisible: the manifest would
 * keep advertising a URL that 404s while every other test stayed green. Two of the strings here are
 * unavoidably two spellings of the same route (a Spring pattern has placeholders, an advertised URI
 * has values), so what actually holds them together is a test that follows an advertised URI and
 * expects the bytes — see {@code SkillVersionPinIT}.
 *
 * <p><strong>The version lives inside the {@code {name}} segment.</strong> A path pattern cannot put
 * a literal {@code @} between two variables, so {@code {name}} carries an optional {@code @version}
 * suffix and is split by {@link SkillAddress}. Every sub-route therefore hangs off one shared
 * prefix, which is what makes {@code /body} and {@code /files/…} impossible to spell differently in
 * the two places.
 */
final class SkillRoutes {

    private SkillRoutes() {
    }

    /**
     * §4.1's API plane, and the only place its prefix is written down.
     *
     * <p>The {@code /api} segment is what separates this plane from the other two a request can
     * arrive on — {@code /web} for the browser, {@code /inner} for the operator — so that a path
     * alone says which credential it expects. Both the controller mapping and every advertised URI
     * are built from this constant, which is why a rename here cannot leave the manifest pointing
     * at the old one.
     */
    static final String BASE = "/api/v1/skills";

    /*
     * The templates below are RELATIVE to BASE, because Spring appends a method's path to its
     * class's — so {@code @RequestMapping(BASE)} plus {@code @GetMapping(SKILL)} is one route, while
     * a SKILL that repeated the base would be routed at /api/v1/skills/api/v1/skills/… and match
     * nothing.
     * The builders further down do need the whole path, which is why BASE is spelled out there.
     */

    /** One skill. The name may carry {@code @number} or {@code @sha256:…}; omitted means latest. */
    static final String SKILL = "/{namespace}/{name}";

    static final String BODY = SKILL + "/body";
    static final String FILES = SKILL + "/files/{*relpath}";

    /** {@code /api/v1/skills/<ns>/<name>@<number>/body} — the L2 address, with the version pinned. */
    static String bodyAt(String namespaceSlug, String name, int number) {
        return pinned(namespaceSlug, name, number) + "/body";
    }

    /** {@code /api/v1/skills/<ns>/<name>@<number>/files/<relpath>} — the L3 address. */
    static String fileAt(String namespaceSlug, String name, int number, String relpath) {
        return pinned(namespaceSlug, name, number) + "/files/" + encodePath(relpath);
    }

    /**
     * The part every advertised URI shares: the address with the version resolved and written in.
     *
     * <p>Always a number rather than the digest, because the number is what a client can carry
     * forward most cheaply and it is equally immutable (ADR 0012). The digest remains available in
     * the response for a client that would rather pin by content.
     */
    private static String pinned(String namespaceSlug, String name, int number) {
        return BASE + "/" + encodeSegment(namespaceSlug) + "/" + encodeSegment(name) + "@" + number;
    }

    /**
     * A single path segment, encoded.
     *
     * <p>Names and slugs may be non-ASCII (M5 permits it), and a space or a {@code %} left raw would
     * make the advertised URI invalid — yet a client is told to fetch it verbatim. Spring's allowed
     * set for a segment is exactly {@code pchar}, so {@code @} and {@code :} survive untouched and
     * the all-ASCII example in §4.2 comes out byte for byte as written.
     */
    private static String encodeSegment(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }

    /**
     * A relpath, encoded as a path rather than as one segment: {@code references/x.md} must stay two
     * segments, because {@code %2F} is rejected outright by the servlet firewall before routing.
     * Storage holds the relpath with {@code /}, and M5 guarantees no leading or empty segment, so
     * this is equivalent to encoding each segment and rejoining.
     *
     * <p>Applied to the stored relpath exactly once. It is not idempotent — {@code %} becomes
     * {@code %25} — so a value that had already been through it would be corrupted.
     */
    private static String encodePath(String relpath) {
        return UriUtils.encodePath(relpath, StandardCharsets.UTF_8);
    }
}
