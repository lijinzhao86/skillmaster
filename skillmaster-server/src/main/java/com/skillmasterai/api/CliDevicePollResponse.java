package com.skillmasterai.api;

import com.skillmasterai.common.Timestamps;
import com.skillmasterai.modules.token.CliSignIn;

/**
 * One poll's answer: the status, and the credential only when the answer is yes.
 *
 * <p><strong>Five statuses, one body.</strong> {@code pending}, {@code approved}, {@code denied},
 * {@code expired} and {@code used} are what the CLI branches on; the credential's five fields are
 * null in four of them. The same shape for every answer is what lets the CLI read a status without
 * first deciding which of two response formats it got.
 *
 * <p>The credential's fields are the old exchange's, unchanged — the terminal prints the account,
 * the machine's name, the scopes and the earliest expiry, and this is the only response any of them
 * will ever appear in.
 */
record CliDevicePollResponse(String status, String accessToken, String expiresAt, String scope,
        String name, String username) {

    static CliDevicePollResponse of(String status) {
        return new CliDevicePollResponse(status, null, null, null, null, null);
    }

    static CliDevicePollResponse approved(CliSignIn.Credential credential, String username) {
        return new CliDevicePollResponse(CliSignIn.APPROVED, credential.token(),
                Timestamps.format(credential.expiresAt()), String.join(" ", credential.scopes()),
                credential.name(), username);
    }
}
