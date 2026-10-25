package com.skillmasterai.common;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shape of a version name: Semantic Versioning 2.0.0, and nothing more than its shape.
 *
 * <p><strong>Shape only, deliberately.</strong> A version name is the author's declaration that this
 * release relates to the last one in some particular way, and no server can check that claim — it
 * does not know what changed, or what the author considers breaking. What this class can check is
 * whether the string is a version at all, which is what makes {@code @1.2.3} mean one thing for
 * ever. The guarantee that a request returns specific bytes is the digest's, not this one's
 * (ADR 0033).
 *
 * <p>The grammar is SemVer 2.0.0's official expression, unmodified. It is deliberately the same
 * judgement the host's own plugin loader applies — {@code 1.0}, {@code v1.0.0} and {@code latest}
 * are refused there too — so that a version name that works in one place works in the other.
 *
 * <p><strong>Not {@link Comparable}.</strong> Nothing orders by version: a version list is ordered
 * by submission ({@code skill_version.number}), and {@code latest} is a pointer that may move
 * backwards. A {@code compareTo} would exist only to be misused by someone reaching for "the
 * greatest version", which is the one reading ADR 0033 rules out.
 *
 * <p>Lives in {@code common} rather than in a module because two of them need it and neither may
 * depend on the other: {@code modules.ingest} validates the frontmatter field, and {@code api}
 * parses the address suffix (§2.5 rule ①).
 */
public final class SemVer {

    /**
     * SemVer 2.0.0's official regular expression, with the three capture groups left in place: major,
     * minor and patch require no leading zero, a prerelease part is dot-separated identifiers where a
     * numeric one also rejects leading zeros, and build metadata is dot-separated alphanumerics.
     * {@code \d} is ASCII-only here, which is what the specification means.
     */
    private static final Pattern PATTERN = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)"
                    + "(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?"
                    + "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$");

    private SemVer() {
    }

    /**
     * Whether this string is a version name.
     *
     * @param candidate the declared version, or null when the author declared none — which is a valid
     *                  thing to do and not a version, so this answers false rather than throwing
     */
    public static boolean isValid(String candidate) {
        return candidate != null && PATTERN.matcher(candidate).matches();
    }

    /** The three numbers of a version, without the parts that follow them. */
    public record Core(int major, int minor, int patch) {
    }

    /**
     * The three numbers of a valid version, or empty when the string is not one.
     *
     * <p>Returned as numbers rather than as the substrings so a caller that increments them cannot
     * reproduce {@code 01} — the grammar forbids a leading zero, and string arithmetic would create
     * exactly the invalid version this class exists to reject.
     */
    public static Optional<Core> core(String version) {
        if (version == null) {
            return Optional.empty();
        }
        Matcher matcher = PATTERN.matcher(version);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Core(Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))));
    }

    /**
     * Whether this version carries a prerelease part — {@code 1.0.0-rc.1} does, and {@code 1.0.0} and
     * {@code 1.0.0+build-7} do not. Build metadata is not a prerelease (SemVer 2.0.0 §9), and the
     * {@code -} inside it is not one either.
     *
     * <p>A string that is not a version at all answers false; callers here have already validated.
     */
    public static boolean isPrerelease(String version) {
        if (version == null) {
            return false;
        }
        Matcher matcher = PATTERN.matcher(version);
        return matcher.matches() && matcher.group(4) != null;
    }
}
