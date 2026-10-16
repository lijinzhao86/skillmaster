package com.skillmasterai.api;

import com.skillmasterai.modules.token.CliSignIn;

/**
 * Where the browser goes once the person has agreed: the registered loopback address, with the
 * one-time code on it.
 *
 * <p><strong>An address, and not the credential.</strong> The value that ends up in this URL lands in
 * browser history, in a Referer and in whatever log decides to keep it — so the code is short-lived,
 * single-use, and worth nothing without the PKCE verifier the CLI is holding. The credential itself
 * arrives at the exchange and nowhere else.
 */
record CliSignInStartedResponse(String redirectTo) {

    static CliSignInStartedResponse of(CliSignIn.Started started) {
        return new CliSignInStartedResponse(started.redirectTo());
    }
}
