package com.skillmasterai.modules.token;

import java.time.Duration;
import java.util.Objects;

/**
 * How long this deployment's tokens live, and how much of a race it will forgive.
 *
 * <p>A value type rather than five constants, for the reason §3.1 gives: these are numbers a
 * deployment may need to change, and a number that can only be changed by editing a module is a
 * number that will be edited in a hurry one day. The module cannot read configuration itself, so
 * {@code config} builds this and hands it over — the same arrangement M1 has with its cipher keys.
 *
 * <p><strong>Two of the five are not lifetimes</strong> in the sense of "how long a thing lasts":
 * {@code refreshTokenAbsolute} is a ceiling on the whole authorization rather than on one token, and
 * {@code refreshReplayGrace} is a window in which a reused token is treated as a race instead of an
 * attack. They are here because all five are the same kind of number — an operational dial on this
 * module's behaviour — and splitting them across two types would be a distinction nobody would
 * remember.
 *
 * @param accessToken         the short-lived credential every request carries. Also the size of the
 *                            window a stolen token is worth something in, and the frequency the CLI
 *                            refreshes at — which is why it is not longer.
 * @param refreshTokenIdle    how long a refresh token survives **unused**. The design's other ruler
 *                            (ADR 0024): a credential nobody has touched for this long is gone.
 * @param refreshTokenAbsolute a ceiling on the whole authorization, however much it is used. Its
 *                            value is not about theft at all — it is the thing that guarantees a
 *                            forgotten credential stops working, and that a person is asked to
 *                            confirm "yes, still me" at least this often.
 * @param authorizationCode   how long an authorization code is good for. Minutes: its life only has
 *                            to cover the trip from the browser back to the machine that asked.
 * @param refreshReplayGrace  how long after a rotation a presented-but-spent refresh token is
 *                            treated as a race rather than a replay (ADR 0024). Seconds; the price
 *                            of the window is that a stolen token used inside it goes unnoticed.
 */
public record TokenPolicy(
        Duration accessToken,
        Duration refreshTokenIdle,
        Duration refreshTokenAbsolute,
        Duration authorizationCode,
        Duration refreshReplayGrace) {

    public TokenPolicy {
        requirePositive(accessToken, "access token lifetime");
        requirePositive(refreshTokenIdle, "refresh token idle lifetime");
        requirePositive(refreshTokenAbsolute, "refresh token absolute ceiling");
        requirePositive(authorizationCode, "authorization code lifetime");
        requirePositive(refreshReplayGrace, "refresh replay grace window");

        // Caught at startup rather than in a year's time. A ceiling below the idle lifetime means
        // the idle rule can never fire, so a deployment that set them the wrong way round would have
        // one of its two rulers silently disabled.
        if (refreshTokenAbsolute.compareTo(refreshTokenIdle) < 0) {
            throw new IllegalStateException(
                    "the refresh token's absolute ceiling (" + refreshTokenAbsolute
                            + ") is shorter than its idle lifetime (" + refreshTokenIdle
                            + "), which makes the idle rule unreachable");
        }
    }

    private static void requirePositive(Duration value, String what) {
        Objects.requireNonNull(value, what + " is required");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalStateException(what + " must be positive, got " + value);
        }
    }
}
