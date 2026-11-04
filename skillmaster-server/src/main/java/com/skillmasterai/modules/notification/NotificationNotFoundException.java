package com.skillmasterai.modules.notification;

/**
 * No such notification, or not this recipient's — one answer for both, so an id nobody holds is
 * indistinguishable from one somebody else holds.
 */
public class NotificationNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotificationNotFoundException() {
        super("no such notification");
    }
}
