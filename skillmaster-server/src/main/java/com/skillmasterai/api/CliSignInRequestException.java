package com.skillmasterai.api;

/**
 * The sign-in screen's request was missing something it cannot do without — in practice, the name.
 *
 * <p><strong>Why the server checks what the screen already prevents.</strong> The console disables
 * the button while the field is empty, which stops a typo and nothing else: the endpoint is reachable
 * without the page. And a blank name is not a cosmetic hole — the name is how a repeated login finds
 * the one it replaces (v4 Q7), so every unnamed machine would look like the same machine and end the
 * others' logins.
 *
 * <p>Shaped like {@link ApiKeyRequestException}: the plane's own refusal, answered as a 400 that names
 * the field, rather than the 500 a bare exception would produce for a request the caller can fix.
 */
class CliSignInRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    CliSignInRequestException(String message) {
        super(message);
    }
}
