package com.skillmasterai.api;

import com.skillmasterai.modules.version.VersionPin;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A skill name as §4.1 spells it in a path: the name, optionally carrying an {@code @version} suffix.
 *
 * <p>The suffix is split at the <strong>first</strong> {@code @}, which is unambiguous only because
 * M5 refuses a skill whose name holds one — the character is reserved for exactly this. Everything
 * after it must be a canonical positive integer or a prefixed SHA-256 digest.
 *
 * <p><strong>An unrecognised suffix is not an error, it is an address that names nothing.</strong>
 * {@code parse} returns empty and the caller answers the same 404 it answers for a skill that does
 * not exist. §4.1 is explicit that all four failures — no such skill, not yours, soft-deleted,
 * version does not exist — share one answer, because every extra code is a place to probe from. A
 * malformed suffix is the same kind of nothing.
 *
 * <p>Deliberately tolerant of nothing else: {@code @03}, {@code @+3} and {@code @0} are rejected
 * rather than parsed. {@code Integer.parseInt} would accept the first two, and canonicalising them
 * would mean two spellings of one address — which is the drift §4.1's single-answer rule exists to
 * prevent, one level down.
 *
 * <p>The {@code sha256:} prefix is stripped here because the pin carries storage's bare lowercase
 * hex; the prefix is a presentation concern the API adds everywhere else too.
 */
record SkillAddress(String name, VersionPin pin) {

    private static final String DIGEST_PREFIX = "sha256:";

    /**
     * A canonical positive integer, bounded to nine digits so that parsing it cannot overflow — a
     * bound no real skill will approach, and one that rejects {@code @0000000003} along with
     * everything else non-canonical.
     */
    private static final Pattern NUMBER = Pattern.compile("[1-9][0-9]{0,8}");

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    /** @param segment the path segment as the container decoded it, {@code @} included */
    static Optional<SkillAddress> parse(String segment) {
        int at = segment.indexOf('@');
        if (at < 0) {
            return Optional.of(new SkillAddress(segment, new VersionPin.Latest()));
        }

        String name = segment.substring(0, at);
        String suffix = segment.substring(at + 1);

        if (NUMBER.matcher(suffix).matches()) {
            return Optional.of(new SkillAddress(name, new VersionPin.Number(Integer.parseInt(suffix))));
        }
        if (suffix.startsWith(DIGEST_PREFIX)) {
            String hex = suffix.substring(DIGEST_PREFIX.length());
            if (SHA256_HEX.matcher(hex).matches()) {
                return Optional.of(new SkillAddress(name, new VersionPin.Digest(hex)));
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
