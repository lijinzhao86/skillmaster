package com.skillmasterai.api;

import com.skillmasterai.common.SemVer;
import com.skillmasterai.modules.version.VersionPin;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A skill name as §4.1 spells it in a path: the name, optionally carrying an {@code @version} suffix.
 *
 * <p>The suffix is split at the <strong>first</strong> {@code @}, which is unambiguous only because
 * M5 refuses a skill whose name holds one — the character is reserved for exactly this. Everything
 * after it must be a semver version name or a prefixed SHA-256 digest.
 *
 * <p><strong>An unrecognised suffix is not an error, it is an address that names nothing.</strong>
 * {@code parse} returns empty and the caller answers the same 404 it answers for a skill that does
 * not exist. §4.1 is explicit that all four failures — no such skill, not yours, soft-deleted,
 * version does not exist — share one answer, because every extra code is a place to probe from. A
 * malformed suffix is the same kind of nothing — which is also why {@code @3}, an address that was
 * legal before ADR 0033 replaced the integer alias, is now indistinguishable from a typo.
 *
 * <p><strong>A malformed version <em>header</em> is a 400, not this 404</strong> (see
 * {@code SkillVersionHeader}). The asymmetry is deliberate: a path may be a URL somebody pasted, and
 * an address that does not resolve has nothing more to say; a header is a parameter the caller
 * computed, and saying it is malformed reveals nothing while a 404 would send them looking for a
 * skill that is there.
 *
 * <p>The {@code sha256:} prefix is stripped here because the pin carries storage's bare lowercase
 * hex; the prefix is a presentation concern the API adds everywhere else too.
 */
record SkillAddress(String name, VersionPin pin) {

    private static final String DIGEST_PREFIX = "sha256:";

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    /** @param segment the path segment as the container decoded it, {@code @} included */
    static Optional<SkillAddress> parse(String segment) {
        int at = segment.indexOf('@');
        if (at < 0) {
            return Optional.of(new SkillAddress(segment, new VersionPin.Latest()));
        }

        String name = segment.substring(0, at);
        return pinOf(segment.substring(at + 1))
                .map(pin -> new SkillAddress(name, pin));
    }

    /**
     * The suffix after {@code @}, as a pin — or empty when it is neither a version name nor a digest.
     *
     * <p>Shared with the version header, which carries the same value under a different name (§4.2).
     * One grammar, one parser: two would eventually disagree about something like build metadata.
     *
     * <p>The name is not canonicalised, and there is nothing to canonicalise — the grammar has one
     * spelling per version, so {@code 1.0.0} has no aliases to fold together.
     */
    static Optional<VersionPin> pinOf(String suffix) {
        if (SemVer.isValid(suffix)) {
            return Optional.of(new VersionPin.Named(suffix));
        }
        if (suffix.startsWith(DIGEST_PREFIX)) {
            String hex = suffix.substring(DIGEST_PREFIX.length());
            if (SHA256_HEX.matcher(hex).matches()) {
                return Optional.of(new VersionPin.Digest(hex));
            }
        }
        return Optional.empty();
    }

    /**
     * The address, or §4.1's one 404 — {@link #parse}'s empty folded into the answer both planes give
     * an address that names nothing.
     *
     * <p>Here rather than as a private helper on each controller because it is the rule that an
     * unreadable suffix is indistinguishable from a missing skill, and that rule is §4.1's rather than
     * either plane's. Written twice it would be two chances to answer differently.
     */
    static SkillAddress orNotFound(String segment) {
        return parse(segment).orElseThrow(SkillNotFoundException::new);
    }
}
