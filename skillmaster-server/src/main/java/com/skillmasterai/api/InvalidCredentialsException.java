package com.skillmasterai.api;

/**
 * A login attempt that did not authenticate.
 *
 * <p>An exception rather than a status returned from the controller, so that the response shape is
 * built in one place — the same reason {@link SkillNotFoundException} exists. The controller also
 * has nothing useful to say about which of the three failure modes it was: the use case collapses
 * them into an empty {@code Optional} precisely so that nobody downstream can tell them apart.
 */
final class InvalidCredentialsException extends RuntimeException {

    InvalidCredentialsException() {
        super("the phone number or password is incorrect");
    }
}
