package com.skillmasterai.modules.version;

import java.util.Optional;

/**
 * Which of a skill's versions an address asked for (§4.1).
 *
 * <p>Three forms, and only the first is not a pin: an address with no {@code @} suffix means
 * {@code latest}, which is resolved afresh on every request, so a task that keeps entering the same
 * address drifts as soon as anyone publishes. The other two are pins — data the client carries, not
 * state the server keeps (ADR 0012) — and that is what makes a manifest and the bytes fetched from
 * it stay in agreement.
 *
 * <p>Sealed so resolution must handle every form: a new pin kind added later becomes a compile error
 * at each place that resolves one, rather than a lookup that silently falls through to the default.
 *
 * <p>Lives in M7 rather than the use-case layer because the versions it selects are rows of a table
 * M7 owns, and because §2.5 forbids a module depending on a use case. The wire spelling that
 * produces one is the API's business — see {@code SkillAddress}.
 */
public sealed interface VersionPin {

    /** No suffix: the skill's current version, re-resolved on every request. */
    record Latest() implements VersionPin {
    }

    /**
     * How this pin is spelled in an address, without the {@code @}: {@code 1.2.3} or
     * {@code sha256:…}.
     *
     * <p>Empty for {@link Latest}, and empty is the honest answer rather than a placeholder:
     * {@code latest} is the <em>absence</em> of a suffix, so there is nothing to spell. Callers that
     * need a non-empty value necessarily have a pin that names one version, and can say so with
     * {@code orElseThrow} — the same assumption {@code SkillVersionService#versionFor} enforces for
     * the two operations that act on one version.
     */
    default Optional<String> suffix() {
        return switch (this) {
            case Latest() -> Optional.empty();
            case Named(String version) -> Optional.of(version);
            case Digest(String sha256Hex) -> Optional.of("sha256:" + sha256Hex);
        };
    }

    /**
     * {@code @1.2.3} — pinned to a version name the author declared.
     *
     * <p>The alias rather than the identity, and immutable: {@code UNIQUE (skill_id, version)} makes
     * the name a stable label for one piece of content, so {@code @1.2.3} means the same bytes for
     * ever (ADR 0033). A version whose author declared no name is not addressable this way at all —
     * that is what {@link Digest} is for.
     *
     * <p>Not validated here: whether this string is a well-formed semver is the address's business,
     * and by the time a pin exists that has been decided. What matters below is only whether some
     * version of this skill carries it.
     */
    record Named(String version) implements VersionPin {
    }

    /**
     * {@code @sha256:…} — pinned to content, the identity rather than the alias.
     *
     * <p>Bare lowercase hex, which is how storage writes a digest (ADR 0005); the {@code sha256:}
     * prefix an address carries is a presentation concern the API strips.
     *
     * <p>Also the only way to address a version that declares no name (ADR 0033).
     */
    record Digest(String sha256Hex) implements VersionPin {
    }
}
