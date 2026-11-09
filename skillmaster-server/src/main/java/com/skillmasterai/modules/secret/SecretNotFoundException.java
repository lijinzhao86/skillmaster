package com.skillmasterai.modules.secret;

/**
 * No such credential, or not the caller's — one answer for both, like every other 404.
 *
 * <p>Deliberately not a {@code RequestException}: the status is 404, and the shared handler would
 * otherwise fix this at 400. {@code SecretExceptionAdvice} translates it to
 * {@code credential_not_found}.
 */
public class SecretNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SecretNotFoundException() {
        this("no such credential");
    }

    public SecretNotFoundException(String message) {
        super(message);
    }
}
