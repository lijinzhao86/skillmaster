package com.skillmasterai.modules.notification;

/**
 * A notification that exists and cannot be answered as asked — 409 on the wire.
 *
 * <p>{@code reason} is the machine-readable half the CLI and console switch on:
 * {@code expired}, {@code already_resolved}, {@code not_an_invitation},
 * {@code not_an_access_request}.
 */
public class NotificationStateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    public NotificationStateException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
