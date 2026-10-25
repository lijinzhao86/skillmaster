package com.skillmasterai.modules.version;

import com.skillmasterai.common.ApiError;
import com.skillmasterai.common.RequestException;
import com.skillmasterai.common.SemVer;
import java.util.Locale;
import java.util.Optional;

/**
 * How big a change is, as the author declares it — {@code major} / {@code minor} / {@code patch}.
 *
 * <p><strong>It is not a version number.</strong> The number is computed at publish time from this
 * and the version that is live at that moment (ADR 0091); a draft that declares {@code minor} holds
 * no digit of its own. That is what keeps this apart from the pre-allocation ADR 0055 rejected.
 *
 * <p>The three names exist in exactly two places: this enum, and the CHECK constraint in V29. The
 * wire spelling <em>is</em> {@link #name()} lower-cased — no second spelling to drift, the same rule
 * {@code Scopes} follows.
 */
public enum ReleaseType {
    MAJOR, MINOR, PATCH;

    /** The wire spelling: {@code major}, {@code minor}, {@code patch}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The number this level produces from a base version, or empty when the base cannot produce one.
     *
     * <p>Empty means <em>ask the publisher for a number</em>, and there are two such bases: one that
     * carries a prerelease part (SemVer gives no single answer for what follows {@code 1.0.0-rc.1},
     * and ADR 0091 refuses to invent one), and one that is an invalid or absent string (a version
     * published before names existed). Build metadata is dropped rather than kept: {@code 1.2.3+sha}
     * bumps to {@code 1.2.4}, because the metadata is not part of precedence.
     *
     * @param base the version that is live right now, or null when nothing is live
     */
    public Optional<String> applyTo(String base) {
        if (SemVer.isPrerelease(base)) {
            return Optional.empty();
        }
        return SemVer.core(base).map(core -> switch (this) {
            case MAJOR -> (core.major() + 1) + ".0.0";
            case MINOR -> core.major() + "." + (core.minor() + 1) + ".0";
            case PATCH -> core.major() + "." + core.minor() + "." + (core.patch() + 1);
        });
    }

    /**
     * The canonical spelling of a declared type, or null when none was declared — the form every
     * write path stores.
     *
     * <p>A value that names none of the three is a bad field, not a bad request: answering 400 with
     * the field named is what lets a CLI say {@code --release bogus} instead of "something failed".
     */
    public static String canonicalOrNull(String wire) {
        if (wire == null || wire.isBlank()) {
            return null;
        }
        return from(wire).map(ReleaseType::wire).orElseThrow(() -> new RequestException(
                com.skillmasterai.common.ErrorCode.INVALID_REQUEST,
                "'" + wire + "' is not a release type; expected major, minor or patch",
                java.util.List.of(new ApiError.Detail("release_type", "invalid_value"))));
    }

    /**
     * Parses a wire spelling, case-insensitively — a request body is written by people and by CLIs,
     * and neither is served by rejecting {@code MINOR}.
     *
     * @return the type, or empty when the string names none of the three
     */
    public static Optional<ReleaseType> from(String wire) {
        if (wire == null) {
            return Optional.empty();
        }
        String normalised = wire.trim().toUpperCase(Locale.ROOT);
        for (ReleaseType type : values()) {
            if (type.name().equals(normalised)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
