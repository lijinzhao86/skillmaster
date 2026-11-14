package com.skillmasterai.api;

import com.skillmasterai.modules.token.CliSignIn;

/**
 * What the CLI keeps from a question it just asked.
 *
 * <p><strong>Two values, and they go to different places.</strong> The ticket goes in the link the
 * person opens; the secret never leaves the process that asked. Neither is enough on its own — the
 * ticket is in a URL and the secret is in the CLI — and the poll proves both plus the PKCE verifier.
 *
 * <p>{@code expiresIn} and {@code interval} are seconds rather than an absolute time because the
 * CLI schedules by them: it stops at the first and waits the second between asks.
 */
record CliDeviceSignInStartedResponse(String ticket, String deviceSecret, long expiresIn,
        long interval) {

    static CliDeviceSignInStartedResponse of(CliSignIn.Started started) {
        return new CliDeviceSignInStartedResponse(started.ticket(), started.deviceSecret(),
                started.expiresInSeconds(), started.intervalSeconds());
    }
}
