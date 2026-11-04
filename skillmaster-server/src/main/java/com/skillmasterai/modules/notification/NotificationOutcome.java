package com.skillmasterai.modules.notification;

/**
 * How a request-shaped notification ended.
 *
 * <p>The wire names are the contract; the constants are not. The same set is a CHECK on the column,
 * so a value that does not appear here cannot be written.
 */
public enum NotificationOutcome {

    ACCEPTED("accepted"),
    DECLINED("declined"),
    EXPIRED("expired"),
    REVOKED("revoked"),
    APPROVED("approved"),
    REJECTED("rejected"),
    WITHDRAWN("withdrawn");

    private final String wireName;

    NotificationOutcome(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
